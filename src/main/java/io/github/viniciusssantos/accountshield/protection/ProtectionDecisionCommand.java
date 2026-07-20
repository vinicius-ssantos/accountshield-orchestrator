package io.github.viniciusssantos.accountshield.protection;

import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import java.util.Objects;

public record ProtectionDecisionCommand(
        String accountReference,
        ProtectionEventType eventType,
        RiskSignals signals) {

    public ProtectionDecisionCommand {
        Objects.requireNonNull(accountReference, "accountReference must not be null");
        if (accountReference.isBlank() || accountReference.length() > 128) {
            throw new IllegalArgumentException("accountReference must contain between 1 and 128 characters");
        }
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(signals, "signals must not be null");
    }
}
