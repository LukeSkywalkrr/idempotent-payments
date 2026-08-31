package dev.pratyush.idempotency;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "idempotent-payments", mixinStandardHelpOptions = true,
        description = "Hammer a naive and an idempotent payment handler.")
public final class Main implements Callable<Integer> {
    @Option(names = "--requests", defaultValue = "50")
    int requests;

    @Option(names = "--amount-cents", defaultValue = "2000")
    long amountCents;

    @Option(names = "--currency", defaultValue = "usd")
    String currency;

    @Option(names = "--store", defaultValue = "memory", description = "memory or redis")
    String storeName;

    @Option(names = "--redis-url", defaultValue = "redis://127.0.0.1:6379")
    String redisUrl;

    @Option(names = "--ttl-seconds", defaultValue = "86400")
    long ttlSeconds;

    @Option(names = "--csv", defaultValue = "benchmark.csv")
    Path csv;

    @Option(names = "--serve", description = "Keep the HTTP API running for manual curl requests")
    boolean serve;

    @Option(names = "--port", defaultValue = "8080")
    int port;

    @Override
    public Integer call() throws Exception {
        Duration ttl = Duration.ofSeconds(ttlSeconds);
        if (serve) {
            return serve(ttl);
        }
        DemoRunner runner = new DemoRunner();
        BenchmarkResult baseline;
        BenchmarkResult idempotent;
        try (IdempotencyStore store = createStore()) {
            baseline = runner.run("naive", requests, amountCents, currency,
                    new InMemoryIdempotencyStore(), ttl);
            idempotent = runner.run("idempotency_key", requests, amountCents, currency, store, ttl);
        }
        writeCsv(List.of(baseline, idempotent));
        System.out.println("mode              requests  successes  charges  replays  conflicts  elapsed_ms");
        print(baseline);
        print(idempotent);
        System.out.println("CSV: " + csv.toAbsolutePath());
        return idempotent.createdCharges() == 1 ? 0 : 1;
    }

    private Integer serve(Duration ttl) throws Exception {
        Ledger ledger = new Ledger();
        try (IdempotencyStore store = createStore();
             PaymentHttpServer server = new PaymentHttpServer(
                     new PaymentProcessor(ledger, store, ttl), port)) {
            server.start();
            System.out.println("Payment API listening on http://127.0.0.1:" + server.port());
            System.out.println("Press Ctrl+C to stop.");
            new CountDownLatch(1).await();
        }
        return 0;
    }

    private IdempotencyStore createStore() {
        return switch (storeName.toLowerCase()) {
            case "memory" -> new InMemoryIdempotencyStore();
            case "redis" -> new RedisIdempotencyStore(redisUrl);
            default -> throw new CommandLine.ParameterException(new CommandLine(this),
                    "--store must be memory or redis");
        };
    }

    private void writeCsv(List<BenchmarkResult> results) throws Exception {
        StringBuilder output = new StringBuilder(
                "mode,requests,successful_responses,created_charges,replayed_responses,conflicts,elapsed_ms\n");
        for (BenchmarkResult result : results) {
            output.append("%s,%d,%d,%d,%d,%d,%d%n".formatted(
                    result.mode(), result.requests(), result.successfulResponses(),
                    result.createdCharges(), result.replayedResponses(), result.conflicts(),
                    result.elapsedMillis()));
        }
        Files.writeString(csv, output.toString());
    }

    private static void print(BenchmarkResult result) {
        System.out.printf("%-17s %8d %10d %8d %8d %10d %11d%n",
                result.mode(), result.requests(), result.successfulResponses(),
                result.createdCharges(), result.replayedResponses(), result.conflicts(),
                result.elapsedMillis());
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
