package io.github.viniciusssantos.accountshield.policy;

import java.time.Instant;
import java.util.UUID;

public record PolicyVersionSummary(
        UUID id,
        String policyKey,
        String version,
        PolicyStatus status,
        Short allowMaxScore,
        Short stepUpMaxScore,
        Short recoveryMaxScore,
        Instant createdAt,
        Instant activatedAt) {

    public PolicyVersionSummary(
            UUID id,
            String policyKey,
            String version,
            PolicyStatus status,
            Short allowMaxScore,
            Short stepUpMaxScore,
            Instant createdAt,
            Instant activatedAt) {
        this(id, policyKey, version, status, allowMaxScore, stepUpMaxScore,
                (short) 89, createdAt, activatedAt);
    }
}
