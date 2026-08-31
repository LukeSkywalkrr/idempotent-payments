package dev.pratyush.idempotency;

import java.time.Duration;
import java.util.Optional;

interface IdempotencyStore extends AutoCloseable {
    Decision reserve(String key, String fingerprint, Duration ttl);

    void complete(String key, String fingerprint, String owner, StoredResponse response, Duration ttl);

    void abort(String key, String owner);

    Optional<StoredResponse> await(String key, String fingerprint, Duration timeout);

    @Override
    default void close() {}

    enum State { ACQUIRED, IN_PROGRESS, COMPLETED, MISMATCH }

    record Decision(State state, String owner, StoredResponse response) {
        static Decision acquired(String owner) {
            return new Decision(State.ACQUIRED, owner, null);
        }

        static Decision inProgress() {
            return new Decision(State.IN_PROGRESS, null, null);
        }

        static Decision completed(StoredResponse response) {
            return new Decision(State.COMPLETED, null, response);
        }

        static Decision mismatch() {
            return new Decision(State.MISMATCH, null, null);
        }
    }
}
