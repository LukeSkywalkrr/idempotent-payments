package dev.pratyush.idempotency;

import java.util.concurrent.atomic.AtomicInteger;

final class Ledger {
    private final AtomicInteger charges = new AtomicInteger();

    String createCharge(PaymentRequest request) {
        try {
            Thread.sleep(10);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("charge interrupted", exception);
        }
        return "ch_%04d".formatted(charges.incrementAndGet());
    }

    int chargeCount() {
        return charges.get();
    }
}
