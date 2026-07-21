package io.github.viniciusssantos.accountshield.challenge.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChallengeVerificationRequest(
        @NotBlank @Size(max = 64) String providedCode) {
}
