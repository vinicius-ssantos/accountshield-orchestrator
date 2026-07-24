package io.github.viniciusssantos.accountshield.recovery;

import java.util.Objects;
import java.util.UUID;

public record InitiateRecoveryCommand(UUID authorizationId) {

    public InitiateRecoveryCommand {
        Objects.requireNonNull(authorizationId, "authorizationId must not be null");
    }

    /**
     * Compatibility constructor. The supplied event type is intentionally ignored;
     * recovery derives every operational attribute from the authorization.
     */
    public InitiateRecoveryCommand(
            UUID authorizationId,
            RecoveryEventType ignoredCallerEventType) {
        this(authorizationId);
    }
}
