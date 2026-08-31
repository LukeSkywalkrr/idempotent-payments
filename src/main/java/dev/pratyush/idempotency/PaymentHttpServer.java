package dev.pratyush.idempotency;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.google.gson.Gson;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PaymentHttpServer implements AutoCloseable {
    private static final Gson JSON = new Gson();

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final PaymentProcessor processor;

    PaymentHttpServer(PaymentProcessor processor) throws IOException {
        this(processor, 0);
    }

    PaymentHttpServer(PaymentProcessor processor, int port) throws IOException {
        this.processor = processor;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/payments", exchange -> handle(exchange, true));
        server.createContext("/naive", exchange -> handle(exchange, false));
        server.setExecutor(executor);
    }

    void start() {
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange, boolean idempotent) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            send(exchange, new PaymentResponse(405, "{\"error\":\"method_not_allowed\"}", false));
            return;
        }
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            PaymentRequest request = parseRequest(exchange, body);
            PaymentResponse response;
            if (idempotent) {
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                if (key == null || key.isBlank()) {
                    response = new PaymentResponse(400, "{\"error\":\"missing_idempotency_key\"}", false);
                } else {
                    response = processor.charge(key, request);
                }
            } else {
                response = processor.chargeWithoutIdempotency(request);
            }
            send(exchange, response);
        } catch (RuntimeException exception) {
            send(exchange, new PaymentResponse(500, "{\"error\":\"internal_error\"}", false));
        }
    }

    private static PaymentRequest parseRequest(HttpExchange exchange, String body) {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null && contentType.startsWith("application/json")) {
            return JSON.fromJson(body, PaymentRequest.class);
        }
        Map<String, String> form = parseForm(body);
        return new PaymentRequest(
                Long.parseLong(form.getOrDefault("amount_cents", "2000")),
                form.getOrDefault("currency", "usd"));
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> values = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                values.put(parts[0], parts[1]);
            }
        }
        return values;
    }

    private static void send(HttpExchange exchange, PaymentResponse response) throws IOException {
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (response.replayed()) {
            exchange.getResponseHeaders().set("Idempotent-Replayed", "true");
        }
        exchange.sendResponseHeaders(response.statusCode(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }
}
