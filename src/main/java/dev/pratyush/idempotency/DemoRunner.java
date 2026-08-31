package dev.pratyush.idempotency;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class DemoRunner {
    BenchmarkResult run(String mode, int requests, long amountCents, String currency,
                        IdempotencyStore store, Duration ttl) throws Exception {
        Ledger ledger = new Ledger();
        PaymentProcessor processor = new PaymentProcessor(ledger, store, ttl);
        try (PaymentHttpServer server = new PaymentHttpServer(processor)) {
            server.start();
            return hammer(mode, requests, amountCents, currency, ledger, server.port());
        }
    }

    private BenchmarkResult hammer(String mode, int requestCount, long amountCents,
                                   String currency, Ledger ledger, int port) throws Exception {
        boolean idempotent = mode.equals("idempotency_key");
        String path = idempotent ? "/payments" : "/naive";
        String key = "order-" + UUID.randomUUID();
        String body = "amount_cents=" + amountCents + "&currency=" + currency;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        long startedAt = System.nanoTime();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    HttpRequest.Builder builder = HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + port + path))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                    if (idempotent) {
                        builder.header("Idempotency-Key", key);
                    }
                    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                }));
            }
            ready.await();
            start.countDown();
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get());
            }
            long elapsed = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            int successful = (int) responses.stream().filter(r -> r.statusCode() < 300).count();
            int conflicts = (int) responses.stream().filter(r -> r.statusCode() == 409).count();
            int replayed = (int) responses.stream()
                    .filter(r -> r.headers().firstValue("Idempotent-Replayed").orElse("false").equals("true"))
                    .count();
            if (idempotent && new HashSet<>(responses.stream().map(HttpResponse::body).toList()).size() != 1) {
                throw new IllegalStateException("idempotent responses did not match");
            }
            return new BenchmarkResult(mode, requestCount, successful, ledger.chargeCount(),
                    replayed, conflicts, elapsed);
        }
    }
}
