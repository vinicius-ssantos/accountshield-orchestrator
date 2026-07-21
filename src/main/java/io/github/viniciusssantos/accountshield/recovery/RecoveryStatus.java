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
    ABORTED
}
