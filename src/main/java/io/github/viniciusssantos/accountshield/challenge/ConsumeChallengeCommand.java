package io.github.viniciusssantos.accountshield.challenge;

import java.util.Objects;
import java.util.UUID;

public record ConsumeChallengeCommand(
        UUID challengeId,
        String accountReference,
        ChallengePurpose purpose,
        UUID contextId) {

    public ConsumeChallengeCommand {
        Objects.requireNonNull(challengeId, "challengeId must not be null");
        Objects.requireNonNull(accountReference, "accountReference must not be null");
        if (accountReference.isBlank() || accountReference.length() > 128) {
            throw new IllegalArgumentException(
                    "accountReference must contain between 1 and 128 characters");
        }
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(contextId, "contextId must not be null");
    }
}
