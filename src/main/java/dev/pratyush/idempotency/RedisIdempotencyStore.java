package dev.pratyush.idempotency;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.SetParams;

final class RedisIdempotencyStore implements IdempotencyStore {
    private static final String COMPLETE_SCRIPT = """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
            """;
    private static final String ABORT_SCRIPT = """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            return redis.call('DEL', KEYS[1])
            """;

    private final JedisPooled redis;
    private final String prefix;

    RedisIdempotencyStore(String redisUrl) {
        this.redis = new JedisPooled(redisUrl);
        this.prefix = "idempotent-payments:";
    }

    @Override
    public Decision reserve(String key, String fingerprint, Duration ttl) {
        String owner = UUID.randomUUID().toString();
        String processing = processing(fingerprint, owner);
        String result = redis.set(redisKey(key), processing,
                SetParams.setParams().nx().px(ttl.toMillis()));
        if ("OK".equals(result)) {
            return Decision.acquired(owner);
        }
        return decodeDecision(redis.get(redisKey(key)), fingerprint);
    }

    @Override
    public void complete(String key, String fingerprint, String owner, StoredResponse response, Duration ttl) {
        redis.eval(COMPLETE_SCRIPT,
                List.of(redisKey(key)),
                List.of(processing(fingerprint, owner), completed(fingerprint, response),
                        Long.toString(ttl.toMillis())));
    }

    @Override
    public void abort(String key, String owner) {
        String value = redis.get(redisKey(key));
        if (value != null && value.startsWith("P|") && value.endsWith("|" + owner)) {
            redis.eval(ABORT_SCRIPT, List.of(redisKey(key)), List.of(value));
        }
    }

    @Override
    public Optional<StoredResponse> await(String key, String fingerprint, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Decision decision = decodeDecision(redis.get(redisKey(key)), fingerprint);
            if (decision.state() == State.COMPLETED) {
                return Optional.of(decision.response());
            }
            if (decision.state() == State.MISMATCH) {
                return Optional.empty();
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        redis.close();
    }

    private Decision decodeDecision(String value, String fingerprint) {
        if (value == null) {
            return Decision.inProgress();
        }
        String[] parts = value.split("\\|", 4);
        if (parts.length < 3 || !parts[1].equals(fingerprint)) {
            return Decision.mismatch();
        }
        if (parts[0].equals("P")) {
            return Decision.inProgress();
        }
        int status = Integer.parseInt(parts[2]);
        String body = new String(Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.UTF_8);
        return Decision.completed(new StoredResponse(status, body));
    }

    private String redisKey(String key) {
        return prefix + key;
    }

    private static String processing(String fingerprint, String owner) {
        return "P|" + fingerprint + "|" + owner;
    }

    private static String completed(String fingerprint, StoredResponse response) {
        String body = Base64.getUrlEncoder().encodeToString(response.body().getBytes(StandardCharsets.UTF_8));
        return "C|" + fingerprint + "|" + response.statusCode() + "|" + body;
    }
}
