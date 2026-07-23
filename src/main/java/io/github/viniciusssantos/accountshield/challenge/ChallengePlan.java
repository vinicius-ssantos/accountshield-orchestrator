package io.github.viniciusssantos.accountshield.challenge;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ChallengePlan(
        UUID challengeId,
        String accountReference,
        ChallengeType challengeType,
        ChallengePurpose purpose,
        UUID contextId,
        ChallengeStatus status,
        int maxAttempts,
        int remainingAttempts,
        Instant createdAt,
        Instant expiresAt,
        Instant consumedAt) {

    public ChallengePlan {
        Objects.requireNonNull(challengeId, "challengeId must not be null");
        Objects.requireNonNull(accountReference, "accountReference must not be null");
        if (accountReference.isBlank() || accountReference.length() > 128) {
            throw new IllegalArgumentException("accountReference must contain between 1 and 128 characters");
        }
        Objects.requireNonNull(challengeType, "challengeType must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(contextId, "contextId must not be null");
        Objects.requireNonNull(status, "challengeStatus must not be null");
        if (maxAttempts <= 0 || maxAttempts > 10) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 10");
        }
        if (remainingAttempts < 0 || remainingAttempts > maxAttempts) {
            throw new IllegalArgumentException(
                    "remainingAttempts must be between 0 and maxAttempts");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        if (status == ChallengeStatus.CONSUMED && consumedAt == null) {
            throw new IllegalArgumentException("consumedAt is required for consumed challenges");
        }
        if (status != ChallengeStatus.CONSUMED && consumedAt != null) {
            throw new IllegalArgumentException("consumedAt is allowed only for consumed challenges");
        }
        if (consumedAt != null && consumedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("consumedAt must not be before createdAt");
        }
    }
}
