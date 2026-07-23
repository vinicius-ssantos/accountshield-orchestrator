package io.github.viniciusssantos.accountshield.recovery;

import java.util.Objects;
import java.util.UUID;

public record InitiateRecoveryCommand(UUID protectionRequestId) {

    public InitiateRecoveryCommand {
        Objects.requireNonNull(protectionRequestId, "protectionRequestId must not be null");
    }
}
