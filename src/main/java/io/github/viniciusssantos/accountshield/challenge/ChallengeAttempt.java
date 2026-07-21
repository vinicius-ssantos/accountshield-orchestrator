package io.github.viniciusssantos.accountshield.challenge;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ChallengeAttempt(
        UUID attemptId,
        UUID challengeId,
        ChallengeStatus previousStatus,
        ChallengeStatus newStatus,
        boolean successful,
        String providedCode,
        Instant attemptedAt) {

    public ChallengeAttempt {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(challengeId, "challengeId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(attemptedAt, "attemptedAt must not be null");
    }
}
