package io.github.viniciusssantos.accountshield.recovery;

import java.util.UUID;

public record StepUpChallenge(UUID challengeId, String simulatedCode) {
}
