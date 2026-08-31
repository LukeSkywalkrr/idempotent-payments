package dev.pratyush.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

final class PaymentProcessor {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);

    private final Ledger ledger;
    private final IdempotencyStore store;
    private final Duration ttl;
    private final Runnable afterCharge;

    PaymentProcessor(Ledger ledger, IdempotencyStore store, Duration ttl) {
        this(ledger, store, ttl, () -> {});
    }

    PaymentProcessor(Ledger ledger, IdempotencyStore store, Duration ttl, Runnable afterCharge) {
        this.ledger = ledger;
        this.store = store;
        this.ttl = ttl;
        this.afterCharge = afterCharge;
    }

    PaymentResponse charge(String key, PaymentRequest request) {
        String fingerprint = fingerprint(request);
        for (int attempt = 0; attempt < 3; attempt++) {
            IdempotencyStore.Decision decision = store.reserve(key, fingerprint, ttl);
            switch (decision.state()) {
                case COMPLETED -> {
                    return replay(decision.response());
                }
                case MISMATCH -> {
                    return new PaymentResponse(409,
                            "{\"error\":\"idempotency_key_reused_with_different_payload\"}", false);
                }
                case IN_PROGRESS -> {
                    var stored = store.await(key, fingerprint, WAIT_TIMEOUT);
                    if (stored.isPresent()) {
                        return replay(stored.get());
                    }
                }
                case ACQUIRED -> {
                    try {
                        String chargeId = ledger.createCharge(request);
                        afterCharge.run();
                        StoredResponse response = new StoredResponse(201,
                                "{\"charge_id\":\"%s\",\"amount_cents\":%d,\"currency\":\"%s\"}"
                                        .formatted(chargeId, request.amountCents(), request.currency()));
                        store.complete(key, fingerprint, decision.owner(), response, ttl);
                        return new PaymentResponse(response.statusCode(), response.body(), false);
                    } catch (RuntimeException exception) {
                        store.abort(key, decision.owner());
                        throw exception;
                    }
                }
            }
        }
        return new PaymentResponse(409, "{\"error\":\"request_still_in_progress\"}", false);
    }

    PaymentResponse chargeWithoutIdempotency(PaymentRequest request) {
        String chargeId = ledger.createCharge(request);
        return new PaymentResponse(201,
                "{\"charge_id\":\"%s\",\"amount_cents\":%d,\"currency\":\"%s\"}"
                        .formatted(chargeId, request.amountCents(), request.currency()), false);
    }

    private static PaymentResponse replay(StoredResponse response) {
        return new PaymentResponse(response.statusCode(), response.body(), true);
    }

    private static String fingerprint(PaymentRequest request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(request.canonicalForm().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
