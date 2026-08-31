package dev.pratyush.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HttpDemoTest {
    @Test
    void theRealHttpHandlerPreservesOneSideEffect() throws Exception {
        BenchmarkResult result = new DemoRunner().run(
                "idempotency_key", 50, 2000, "usd",
                new InMemoryIdempotencyStore(), Duration.ofHours(24));

        assertEquals(50, result.successfulResponses());
        assertEquals(1, result.createdCharges());
        assertEquals(49, result.replayedResponses());
        assertEquals(0, result.conflicts());
    }

    @Test
    void theReadmeJsonRequestCanBeCopiedAndReplayed() throws Exception {
        Ledger ledger = new Ledger();
        PaymentProcessor processor = new PaymentProcessor(
                ledger, new InMemoryIdempotencyStore(), Duration.ofHours(24));
        try (PaymentHttpServer server = new PaymentHttpServer(processor)) {
            server.start();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + "/payments"))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "order-cart-alpha")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"amountCents\":2000,\"currency\":\"usd\"}"))
                    .build();
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> first = client.send(request, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> replay = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(201, first.statusCode());
            assertEquals(first.body(), replay.body());
            assertEquals("true", replay.headers().firstValue("Idempotent-Replayed").orElseThrow());
            assertEquals(1, ledger.chargeCount());
        }
    }
}
