package io.github.viniciusssantos.accountshield.challenge;

import java.time.Instant;
import java.util.UUID;

public record ChallengeResult(
        UUID challengeId,
        ChallengeStatus status,
        boolean verified,
        int remainingAttempts,
        Instant expiresAt) {
}
