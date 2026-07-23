package io.github.viniciusssantos.accountshield.recovery;

import java.util.Objects;
import java.util.UUID;

public record InitiateRecoveryCommand(UUID protectionRequestId) {

    public InitiateRecoveryCommand {
        Objects.requireNonNull(protectionRequestId, "protectionRequestId must not be null");
    }

    /**
     * Compatibility constructor. The supplied event type is intentionally ignored;
     * recovery derives it exclusively from the originating decision trace.
     */
    public InitiateRecoveryCommand(
            UUID protectionRequestId,
            RecoveryEventType ignoredCallerEventType) {
        this(protectionRequestId);
    }
}
