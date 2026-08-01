package io.github.viniciusssantos.accountshield.policy;

import java.util.UUID;

public record StepUpChallenge(UUID challengeId, String simulatedCode) {
}
