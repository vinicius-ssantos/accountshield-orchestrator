package io.github.viniciusssantos.accountshield.recovery;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RecoveryAuthorization(
        UUID authorizationId,
        UUID protectionRequestId,
        UUID decisionId,
        String accountReference,
        RecoveryDirective directive,
        int riskScore,
        Instant issuedAt,
        Instant expiresAt,
        Instant consumedAt) {

    public RecoveryAuthorization {
        Objects.requireNonNull(authorizationId, "authorizationId must not be null");
        Objects.requireNonNull(protectionRequestId, "protectionRequestId must not be null");
        Objects.requireNonNull(decisionId, "decisionId must not be null");
        Objects.requireNonNull(accountReference, "accountReference must not be null");
        if (accountReference.isBlank() || accountReference.length() > 128) {
            throw new IllegalArgumentException(
                    "accountReference must contain between 1 and 128 characters");
        }
        Objects.requireNonNull(directive, "directive must not be null");
        if (riskScore < 0 || riskScore > 100) {
            throw new IllegalArgumentException("riskScore must be between 0 and 100");
        }
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
        if (consumedAt != null && consumedAt.isBefore(issuedAt)) {
            throw new IllegalArgumentException("consumedAt must not be before issuedAt");
        }
    }

    public boolean consumed() {
        return consumedAt != null;
    }

    public boolean expiredAt(Instant instant) {
        Objects.requireNonNull(instant, "instant must not be null");
        return !instant.isBefore(expiresAt);
    }
}
