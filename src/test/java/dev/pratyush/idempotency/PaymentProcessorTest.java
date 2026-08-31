package dev.pratyush.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class PaymentProcessorTest {
    private static final PaymentRequest PAYMENT = new PaymentRequest(2000, "usd");

    @Test
    void fiftyConcurrentAttemptsCreateExactlyOneCharge() throws Exception {
        Ledger ledger = new Ledger();
        PaymentProcessor processor = new PaymentProcessor(
                ledger, new InMemoryIdempotencyStore(), Duration.ofHours(24));
        CountDownLatch ready = new CountDownLatch(50);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PaymentResponse>> futures = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 50; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return processor.charge("order-123", PAYMENT);
                }));
            }
            ready.await();
            start.countDown();
            List<PaymentResponse> responses = new ArrayList<>();
            for (Future<PaymentResponse> future : futures) {
                responses.add(future.get());
            }
            assertEquals(1, ledger.chargeCount());
            assertEquals(1, new HashSet<>(responses.stream().map(PaymentResponse::body).toList()).size());
            assertEquals(49, responses.stream().filter(PaymentResponse::replayed).count());
        }
    }

    @Test
    void retryAfterLostResponseReplaysTheStoredResponse() {
        Ledger ledger = new Ledger();
        PaymentProcessor processor = new PaymentProcessor(
                ledger, new InMemoryIdempotencyStore(), Duration.ofHours(24));

        PaymentResponse lost = processor.charge("order-123", PAYMENT);
        PaymentResponse retried = processor.charge("order-123", PAYMENT);

        assertFalse(lost.replayed());
        assertTrue(retried.replayed());
        assertEquals(lost.body(), retried.body());
        assertEquals(1, ledger.chargeCount());
    }

    @Test
    void oneKeyCannotNameTwoDifferentAmounts() {
        Ledger ledger = new Ledger();
        PaymentProcessor processor = new PaymentProcessor(
                ledger, new InMemoryIdempotencyStore(), Duration.ofHours(24));

        processor.charge("order-123", PAYMENT);
        PaymentResponse mismatch = processor.charge("order-123", new PaymentRequest(9900, "usd"));

        assertEquals(409, mismatch.statusCode());
        assertEquals(1, ledger.chargeCount());
    }

    @Test
    void differentKeysRepresentDifferentIntendedPayments() {
        Ledger ledger = new Ledger();
        PaymentProcessor processor = new PaymentProcessor(
                ledger, new InMemoryIdempotencyStore(), Duration.ofHours(24));

        processor.charge("order-123-attempt-a", PAYMENT);
        processor.charge("order-123-attempt-b", PAYMENT);

        assertEquals(2, ledger.chargeCount());
    }

    @Test
    void anExpiredKeyCanExecuteAgain() throws Exception {
        Ledger ledger = new Ledger();
        PaymentProcessor processor = new PaymentProcessor(
                ledger, new InMemoryIdempotencyStore(), Duration.ofMillis(20));

        processor.charge("order-123", PAYMENT);
        Thread.sleep(60);
        processor.charge("order-123", PAYMENT);

        assertEquals(2, ledger.chargeCount());
    }

    @Test
    void crashAfterTheSideEffectExposesTheMissingAtomicBoundary() {
        Ledger ledger = new Ledger();
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        AtomicBoolean crashOnce = new AtomicBoolean(true);
        PaymentProcessor processor = new PaymentProcessor(ledger, store, Duration.ofHours(24), () -> {
            if (crashOnce.getAndSet(false)) {
                throw new IllegalStateException("simulated crash after ledger write");
            }
        });

        assertThrows(IllegalStateException.class, () -> processor.charge("order-123", PAYMENT));
        processor.charge("order-123", PAYMENT);

        assertEquals(2, ledger.chargeCount(),
                "SETNX alone cannot atomically commit an external ledger write and its response");
    }
}
