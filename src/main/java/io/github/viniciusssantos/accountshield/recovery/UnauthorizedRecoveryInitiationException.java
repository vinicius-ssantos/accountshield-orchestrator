package io.github.viniciusssantos.accountshield.recovery;

public class UnauthorizedRecoveryInitiationException extends RuntimeException {

    public UnauthorizedRecoveryInitiationException(String message) {
        super(message);
    }
}
