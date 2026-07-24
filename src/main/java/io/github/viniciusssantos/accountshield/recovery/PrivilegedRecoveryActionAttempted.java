package io.github.viniciusssantos.accountshield.recovery;

import java.util.UUID;

public record PrivilegedRecoveryActionAttempted(
        UUID recoveryId,
        String action,
        String actor,
        boolean authorized) {
}
