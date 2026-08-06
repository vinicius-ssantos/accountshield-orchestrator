package io.github.viniciusssantos.accountshieldsdk.model;

/** Mirrors the server's {@code protection.ProtectionEventType} enum values exactly. */
public enum ProtectionEventType {
    LOGIN_ATTEMPT,
    SENSITIVE_ACTION,
    LOGIN_RECOVERY_ATTEMPT,
    PASSWORD_RESET_ATTEMPT,
    CREDENTIAL_CHANGE_ATTEMPT,
    DEVICE_TRUST_RESET_ATTEMPT
}
