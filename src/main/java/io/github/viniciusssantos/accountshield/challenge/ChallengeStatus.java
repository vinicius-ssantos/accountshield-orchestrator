package io.github.viniciusssantos.accountshield.challenge;

public enum ChallengeStatus {
    PENDING,
    CHALLENGED,
    VERIFIED,
    CONSUMED,
    FAILED,
    EXPIRED;

    public boolean isTerminal() {
        return this == VERIFIED || this == CONSUMED || this == FAILED || this == EXPIRED;
    }
}
