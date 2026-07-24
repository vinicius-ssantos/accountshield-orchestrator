package io.github.viniciusssantos.accountshield.challenge;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ChallengeIssued(
        UUID challengeId,
        String accountReference,
        ChallengeType challengeType,
        ChallengePurpose purpose,
        UUID contextId,
        String issuedCode,
        Instant expiresAt) {

    public ChallengeIssued {
        Objects.requireNonNull(challengeId, "challengeId must not be null");
        Objects.requireNonNull(accountReference, "accountReference must not be null");
        Objects.requireNonNull(challengeType, "challengeType must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(contextId, "contextId must not be null");
        Objects.requireNonNull(issuedCode, "issuedCode must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
}
