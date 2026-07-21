package io.github.viniciusssantos.accountshield.protection;

import java.time.Instant;

public interface IdempotencyGuard {

    IdempotencyResult resolve(String idempotencyKey, String fingerprint, Instant now);

    void record(
            String idempotencyKey,
            String fingerprint,
            String resourceType,
            java.util.UUID resourceId,
            String responsePayload,
            Instant createdAt,
            Instant expiresAt);
}
