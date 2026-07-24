package io.github.viniciusssantos.accountshield.protection;

public enum ProtectionEventType {
    LOGIN_ATTEMPT(false),
    SENSITIVE_ACTION(false),
    LOGIN_RECOVERY_ATTEMPT(true),
    PASSWORD_RESET_ATTEMPT(true),
    CREDENTIAL_CHANGE_ATTEMPT(true),
    DEVICE_TRUST_RESET_ATTEMPT(true);

    private final boolean recoveryRequest;

    ProtectionEventType(boolean recoveryRequest) {
        this.recoveryRequest = recoveryRequest;
    }

    public boolean recoveryRequest() {
        return recoveryRequest;
    }
}
