package io.github.viniciusssantos.accountshield.recovery;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RecoveryFlow(
        UUID recoveryId,
        String accountReference,
        RecoveryEventType eventType,
        RecoveryStatus status,
        RecoveryRiskClassification classification,
        UUID identityChallengeId,
        Instant initiatedAt,
        Instant updatedAt,
        Instant eligibleAfter) {

    public RecoveryFlow {
        Objects.requireNonNull(recoveryId, "recoveryId must not be null");
        Objects.requireNonNull(accountReference, "accountReference must not be null");
        if (accountReference.isBlank() || accountReference.length() > 128) {
            throw new IllegalArgumentException("accountReference must contain between 1 and 128 characters");
        }
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(classification, "classification must not be null");
        Objects.requireNonNull(initiatedAt, "initiatedAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
