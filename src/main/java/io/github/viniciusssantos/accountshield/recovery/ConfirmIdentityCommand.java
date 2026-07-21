package io.github.viniciusssantos.accountshield.recovery;

import java.util.Objects;
import java.util.UUID;

public record ConfirmIdentityCommand(
        UUID recoveryId,
        UUID challengeId) {

    public ConfirmIdentityCommand {
        Objects.requireNonNull(recoveryId, "recoveryId must not be null");
        Objects.requireNonNull(challengeId, "challengeId must not be null");
    }
}
