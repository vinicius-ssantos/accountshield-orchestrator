package io.github.viniciusssantos.accountshield.recovery;

import java.util.Objects;
import java.util.UUID;

public record RecoveryReviewCommand(
        UUID recoveryId,
        RecoveryReviewDecision decision,
        String reviewer,
        UUID stepUpChallengeId) {

    public RecoveryReviewCommand {
        Objects.requireNonNull(recoveryId, "recoveryId must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(reviewer, "reviewer must not be null");
        if (reviewer.isBlank() || reviewer.length() > 128) {
            throw new IllegalArgumentException("reviewer must contain between 1 and 128 characters");
        }
        Objects.requireNonNull(stepUpChallengeId, "stepUpChallengeId must not be null");
    }
}
