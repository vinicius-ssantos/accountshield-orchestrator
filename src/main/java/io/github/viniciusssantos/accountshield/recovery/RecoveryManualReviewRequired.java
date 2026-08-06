package io.github.viniciusssantos.accountshield.recovery;

import java.time.Instant;
import java.util.UUID;

public record RecoveryManualReviewRequired(
        UUID recoveryId,
        String accountReference,
        String classification,
        Instant requiredAt) {
}
