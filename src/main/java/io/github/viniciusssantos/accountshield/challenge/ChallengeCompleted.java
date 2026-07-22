package io.github.viniciusssantos.accountshield.challenge;

import java.time.Instant;
import java.util.UUID;

public record ChallengeCompleted(
        UUID challengeId,
        String accountReference,
        ChallengeType challengeType,
        ChallengeStatus finalStatus,
        Instant completedAt) {
}
