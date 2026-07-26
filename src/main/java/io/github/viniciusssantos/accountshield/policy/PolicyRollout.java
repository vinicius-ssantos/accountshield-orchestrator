package io.github.viniciusssantos.accountshield.policy;

import java.time.Instant;
import java.util.UUID;

public record PolicyRollout(
        UUID id,
        String policyKey,
        String candidateVersion,
        int rolloutPercentage,
        PolicyRolloutStatus status,
        Instant startedAt,
        String startedBy,
        Instant updatedAt,
        Instant rolledBackAt,
        String rolledBackBy) {
}
