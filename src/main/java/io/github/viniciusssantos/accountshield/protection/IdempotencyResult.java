package io.github.viniciusssantos.accountshield.protection;

import java.time.Instant;
import java.util.UUID;

public record IdempotencyResult(
        boolean duplicate,
        UUID protectionRequestId,
        String fingerprint,
        String responsePayload,
        Instant createdAt,
        Instant expiresAt) {

    public IdempotencyResult {
        if (duplicate) {
            if (protectionRequestId == null) {
                throw new IllegalArgumentException("protectionRequestId must not be null for a duplicate");
            }
            if (fingerprint == null) {
                throw new IllegalArgumentException("fingerprint must not be null for a duplicate");
            }
        }
    }

    static IdempotencyResult absent() {
        return new IdempotencyResult(false, null, null, null, null, null);
    }

    static IdempotencyResult duplicate(
            UUID protectionRequestId,
            String fingerprint,
            String responsePayload,
            Instant createdAt,
            Instant expiresAt) {
        return new IdempotencyResult(
                true,
                protectionRequestId,
                fingerprint,
                responsePayload,
                createdAt,
                expiresAt);
    }
}
