package io.github.viniciusssantos.accountshield.protection;

import java.time.Instant;

public class RateLimitExceededException extends RuntimeException {

    private final String accountReference;
    private final Instant retryAfter;

    public RateLimitExceededException(String accountReference, Instant retryAfter) {
        super("rate limit exceeded for account");
        this.accountReference = accountReference;
        this.retryAfter = retryAfter;
    }

    public String accountReference() {
        return accountReference;
    }

    public Instant retryAfter() {
        return retryAfter;
    }
}
