package io.github.viniciusssantos.accountshield.challenge;

import java.util.UUID;

public class InvalidChallengeStateException extends RuntimeException {

    private final UUID challengeId;
    private final ChallengeStatus currentStatus;

    public InvalidChallengeStateException(UUID challengeId, ChallengeStatus currentStatus) {
        super("challenge " + challengeId + " is in state " + currentStatus + " and cannot accept this operation");
        this.challengeId = challengeId;
        this.currentStatus = currentStatus;
    }

    public UUID challengeId() {
        return challengeId;
    }

    public ChallengeStatus currentStatus() {
        return currentStatus;
    }
}
