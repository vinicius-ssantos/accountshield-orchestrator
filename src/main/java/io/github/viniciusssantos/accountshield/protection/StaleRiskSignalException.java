package io.github.viniciusssantos.accountshield.protection;

import java.time.Instant;

public class StaleRiskSignalException extends RuntimeException {

    private final Instant observedAt;

    public StaleRiskSignalException(Instant observedAt) {
        super("risk signal envelope is stale");
        this.observedAt = observedAt;
    }

    public Instant observedAt() {
        return observedAt;
    }
}
