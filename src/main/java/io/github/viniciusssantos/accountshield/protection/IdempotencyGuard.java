package io.github.viniciusssantos.accountshield.protection;

import java.time.Instant;

public interface IdempotencyGuard {

    IdempotencyResult resolve(String clientId, String idempotencyKey, String fingerprint, Instant now);

    void record(
            String clientId,
            String idempotencyKey,
            String fingerprint,
            String resourceType,
            java.util.UUID resourceId,
            String responsePayload,
            Instant createdAt,
            Instant expiresAt);
}
