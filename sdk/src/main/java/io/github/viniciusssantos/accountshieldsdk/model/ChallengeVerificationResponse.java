package io.github.viniciusssantos.accountshieldsdk.model;

import java.time.Instant;
import java.util.UUID;

/** Mirrors {@code POST /api/v1/challenges/{challengeId}/verify}'s 200 response body exactly. */
public record ChallengeVerificationResponse(
        UUID challengeId, ChallengeStatus status, boolean verified, int remainingAttempts, Instant expiresAt) {
}
