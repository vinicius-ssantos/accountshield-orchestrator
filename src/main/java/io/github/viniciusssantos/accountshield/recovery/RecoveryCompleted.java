package io.github.viniciusssantos.accountshield.recovery;

import java.time.Instant;
import java.util.UUID;

public record RecoveryCompleted(
        UUID recoveryId,
        String accountReference,
        String eventType,
        Instant completedAt) {
}
