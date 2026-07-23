package io.github.viniciusssantos.accountshield.challenge.internal.web;

import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ChallengeVerificationRequest(
        @NotBlank @Size(max = 64) String providedCode,
        @NotNull ChallengePurpose purpose,
        @NotNull UUID contextId) {
}
