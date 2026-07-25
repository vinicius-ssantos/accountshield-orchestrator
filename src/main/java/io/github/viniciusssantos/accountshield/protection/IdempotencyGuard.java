package io.github.viniciusssantos.accountshield.protection;

import java.time.Instant;
import java.util.UUID;

public interface IdempotencyGuard {

    /**
     * Atomically claims a (clientId, idempotencyKey) pair before any other side effect runs.
     * Returns {@link IdempotencyResult#absent()} when the caller won the claim and should proceed
     * with the work, later calling {@link #finalizeResult}. Returns a duplicate result when a
     * prior — or just-completed racing — request already owns this key with a matching
     * fingerprint. Throws {@link ConflictingIdempotencyRequestException} when the fingerprint
     * differs. Resource type and TTL policy are owned entirely by the implementation.
     */
    IdempotencyResult claim(String clientId, String idempotencyKey, String fingerprint, UUID resourceId, Instant now);

    /**
     * Records the final response payload for a previously claimed key, making it available to
     * any request that is currently blocked racing for the same key.
     */
    void finalizeResult(String clientId, String idempotencyKey, String responsePayload);
}
