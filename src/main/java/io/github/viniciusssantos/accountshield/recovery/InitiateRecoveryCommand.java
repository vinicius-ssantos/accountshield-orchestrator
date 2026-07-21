package io.github.viniciusssantos.accountshield.recovery;

import java.util.Objects;
import java.util.UUID;

public record InitiateRecoveryCommand(
        String accountReference,
        RecoveryEventType eventType,
        int riskScore) {

    public InitiateRecoveryCommand {
        Objects.requireNonNull(accountReference, "accountReference must not be null");
        if (accountReference.isBlank() || accountReference.length() > 128) {
            throw new IllegalArgumentException("accountReference must contain between 1 and 128 characters");
        }
        Objects.requireNonNull(eventType, "eventType must not be null");
        if (riskScore < 0 || riskScore > 100) {
            throw new IllegalArgumentException("riskScore must be between 0 and 100");
        }
    }
}
