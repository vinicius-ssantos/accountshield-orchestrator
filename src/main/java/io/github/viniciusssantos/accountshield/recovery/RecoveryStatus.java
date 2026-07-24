package io.github.viniciusssantos.accountshield.recovery;

public enum RecoveryStatus {
    INITIATED,
    VERIFYING_IDENTITY,
    IDENTITY_VERIFIED,
    DELAYED,
    MANUAL_REVIEW,
    COMPLETED,
    IDENTITY_FAILED,
    REJECTED,
    ABORTED;

    // must stay in sync with the partial index in V11__harden_recovery_flow_persistence.sql
    public boolean isTerminal() {
        return this == COMPLETED || this == IDENTITY_FAILED || this == REJECTED || this == ABORTED;
    }
}
