package io.github.viniciusssantos.accountshield.challenge;

import java.util.Objects;
import java.util.UUID;

public record CreateChallengeCommand(
        String accountReference,
        ChallengeType challengeType,
        ChallengePurpose purpose,
        UUID contextId) {

    public CreateChallengeCommand {
        Objects.requireNonNull(accountReference, "accountReference must not be null");
        if (accountReference.isBlank() || accountReference.length() > 128) {
            throw new IllegalArgumentException(
                    "accountReference must contain between 1 and 128 characters");
        }
        Objects.requireNonNull(challengeType, "challengeType must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(contextId, "contextId must not be null");
    }
}
