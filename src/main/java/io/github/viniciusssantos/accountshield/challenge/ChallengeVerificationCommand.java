package io.github.viniciusssantos.accountshield.challenge;

import java.util.Objects;
import java.util.UUID;

public record ChallengeVerificationCommand(
        UUID challengeId,
        String providedCode,
        ChallengePurpose purpose,
        UUID contextId) {

    public ChallengeVerificationCommand {
        Objects.requireNonNull(challengeId, "challengeId must not be null");
        if (providedCode == null || providedCode.isBlank() || providedCode.length() > 64) {
            throw new IllegalArgumentException(
                    "providedCode must contain between 1 and 64 characters");
        }
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(contextId, "contextId must not be null");
    }
}
