package io.github.viniciusssantos.accountshield.recovery;

public enum RecoveryDirective {
    LOGIN,
    PASSWORD_RESET,
    CREDENTIAL_CHANGE,
    DEVICE_TRUST_RESET;

    public RecoveryEventType eventType() {
        return RecoveryEventType.valueOf(name());
    }
}
