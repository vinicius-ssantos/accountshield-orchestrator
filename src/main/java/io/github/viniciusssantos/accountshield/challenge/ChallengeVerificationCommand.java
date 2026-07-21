package io.github.viniciusssantos.accountshield.challenge;

import java.util.UUID;

public record ChallengeVerificationCommand(
        UUID challengeId,
        String providedCode) {

    public ChallengeVerificationCommand {
        if (providedCode == null || providedCode.isBlank() || providedCode.length() > 64) {
            throw new IllegalArgumentException(
                    "providedCode must contain between 1 and 64 characters");
        }
    }
}
