package io.github.viniciusssantos.accountshield.recovery;

import java.util.Objects;
import java.util.UUID;

public record InitiateRecoveryCommand(
        UUID protectionRequestId,
        RecoveryEventType eventType) {

    public InitiateRecoveryCommand {
        Objects.requireNonNull(protectionRequestId, "protectionRequestId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
    }
}
