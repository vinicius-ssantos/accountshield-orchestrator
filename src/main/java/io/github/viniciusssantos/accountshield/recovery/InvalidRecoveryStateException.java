package io.github.viniciusssantos.accountshield.recovery;

import java.util.UUID;

public class InvalidRecoveryStateException extends RuntimeException {

    private final UUID recoveryId;
    private final RecoveryStatus currentStatus;
    private final String attemptedAction;

    public InvalidRecoveryStateException(UUID recoveryId, RecoveryStatus currentStatus, String attemptedAction) {
        super("Recovery " + recoveryId + " is in state " + currentStatus + " and cannot accept action: " + attemptedAction);
        this.recoveryId = recoveryId;
        this.currentStatus = currentStatus;
        this.attemptedAction = attemptedAction;
    }

    public UUID recoveryId() {
        return recoveryId;
    }

    public RecoveryStatus currentStatus() {
        return currentStatus;
    }

    public String attemptedAction() {
        return attemptedAction;
    }
}
