package io.github.viniciusssantos.accountshield.challenge;

public class ChallengeUseRejectedException extends RuntimeException {

    public ChallengeUseRejectedException() {
        super("challenge cannot be used for the requested operation");
    }

    public ChallengeUseRejectedException(Throwable cause) {
        super("challenge cannot be used for the requested operation", cause);
    }
}
