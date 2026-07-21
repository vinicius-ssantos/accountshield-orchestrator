package io.github.viniciusssantos.accountshield.risk;

import java.util.Objects;

public record RiskSignals(
        int failedAttempts,
        boolean newDevice,
        boolean impossibleTravel,
        boolean compromisedCredential,
        NetworkRiskLevel networkRiskLevel) {

    private static final int MAX_FAILED_ATTEMPTS = 20;

    public RiskSignals {
        if (failedAttempts < 0 || failedAttempts > MAX_FAILED_ATTEMPTS) {
            throw new IllegalArgumentException("failedAttempts must be between 0 and 20");
        }
        Objects.requireNonNull(networkRiskLevel, "networkRiskLevel must not be null");
    }
}
