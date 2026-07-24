package io.github.viniciusssantos.accountshield.recovery;

import java.util.UUID;

public class RecoveryFlowConflictException extends RuntimeException {

    private final UUID recoveryId;

    public RecoveryFlowConflictException(UUID recoveryId, Throwable cause) {
        super("Recovery " + recoveryId + " was concurrently modified by another request", cause);
        this.recoveryId = recoveryId;
    }

    public UUID recoveryId() {
        return recoveryId;
    }
}
