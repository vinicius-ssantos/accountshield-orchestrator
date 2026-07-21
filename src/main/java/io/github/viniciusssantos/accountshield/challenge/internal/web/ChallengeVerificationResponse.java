package io.github.viniciusssantos.accountshield.challenge.internal.web;

import io.github.viniciusssantos.accountshield.challenge.ChallengeResult;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import java.time.Instant;
import java.util.UUID;

record ChallengeVerificationResponse(
        UUID challengeId,
        ChallengeStatus status,
        boolean verified,
        int remainingAttempts,
        Instant expiresAt) {

    static ChallengeVerificationResponse from(ChallengeResult result) {
        return new ChallengeVerificationResponse(
                result.challengeId(),
                result.status(),
                result.verified(),
                result.remainingAttempts(),
                result.expiresAt());
    }
}
