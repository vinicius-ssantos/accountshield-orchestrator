package io.github.viniciusssantos.accountshield.risk;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record RiskSignalEnvelope(
        RiskSignals signals,
        String provider,
        Instant observedAt,
        SignalConfidence confidence,
        String schemaVersion,
        boolean simulated) {

    public static final String CURRENT_SCHEMA_VERSION = "risk-signal-envelope-1.0";

    public RiskSignalEnvelope {
        Objects.requireNonNull(signals, "signals must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        if (provider.isBlank() || provider.length() > 100) {
            throw new IllegalArgumentException("provider must contain between 1 and 100 characters");
        }
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        Objects.requireNonNull(confidence, "confidence must not be null");
        schemaVersion = schemaVersion == null ? CURRENT_SCHEMA_VERSION : schemaVersion;
        if (schemaVersion.isBlank() || schemaVersion.length() > 40) {
            throw new IllegalArgumentException("schemaVersion must contain between 1 and 40 characters");
        }
    }

    public boolean isStale(Instant now, Duration maxAge) {
        return observedAt.isBefore(now.minus(maxAge));
    }
}
