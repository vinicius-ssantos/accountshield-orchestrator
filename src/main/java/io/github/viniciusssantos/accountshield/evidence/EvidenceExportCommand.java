package io.github.viniciusssantos.accountshield.evidence;

import java.util.Objects;
import java.util.UUID;

public record EvidenceExportCommand(UUID protectionRequestId, String actor, String reason) {

    public EvidenceExportCommand {
        Objects.requireNonNull(protectionRequestId, "protectionRequestId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        if (actor.isBlank() || actor.length() > 128) {
            throw new IllegalArgumentException("actor must contain between 1 and 128 characters");
        }
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("reason must contain between 1 and 500 characters");
        }
    }
}
