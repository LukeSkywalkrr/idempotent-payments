package dev.pratyush.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class InMemoryIdempotencyStore implements IdempotencyStore {
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public Decision reserve(String key, String fingerprint, Duration ttl) {
        AtomicReference<Decision> decision = new AtomicReference<>();
        long expiresAt = System.nanoTime() + ttl.toNanos();
        entries.compute(key, (ignored, existing) -> {
            if (existing == null || existing.expiresAtNanos <= System.nanoTime()) {
                String owner = UUID.randomUUID().toString();
                decision.set(Decision.acquired(owner));
                return new Entry(fingerprint, owner, expiresAt);
            }
            if (!existing.fingerprint.equals(fingerprint)) {
                decision.set(Decision.mismatch());
            } else if (existing.response != null) {
                decision.set(Decision.completed(existing.response));
            } else {
                decision.set(Decision.inProgress());
            }
            return existing;
        });
        return decision.get();
    }

    @Override
    public void complete(String key, String fingerprint, String owner, StoredResponse response, Duration ttl) {
        entries.computeIfPresent(key, (ignored, entry) -> {
            if (entry.owner.equals(owner) && entry.fingerprint.equals(fingerprint)) {
                entry.response = response;
                entry.expiresAtNanos = System.nanoTime() + ttl.toNanos();
                entry.ready.countDown();
            }
            return entry;
        });
    }

    @Override
    public void abort(String key, String owner) {
        entries.computeIfPresent(key, (ignored, entry) -> {
            if (!entry.owner.equals(owner)) {
                return entry;
            }
            entry.ready.countDown();
            return null;
        });
    }

    @Override
    public Optional<StoredResponse> await(String key, String fingerprint, Duration timeout) {
        Entry entry = entries.get(key);
        if (entry == null || !entry.fingerprint.equals(fingerprint)) {
            return Optional.empty();
        }
        try {
            entry.ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        return Optional.ofNullable(entry.response);
    }

    private static final class Entry {
        private final String fingerprint;
        private final String owner;
        private final CountDownLatch ready = new CountDownLatch(1);
        private volatile long expiresAtNanos;
        private volatile StoredResponse response;

        private Entry(String fingerprint, String owner, long expiresAtNanos) {
            this.fingerprint = fingerprint;
            this.owner = owner;
            this.expiresAtNanos = expiresAtNanos;
        }
    }
}
