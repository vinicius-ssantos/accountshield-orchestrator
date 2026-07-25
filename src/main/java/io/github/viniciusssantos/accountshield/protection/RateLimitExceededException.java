package io.github.viniciusssantos.accountshield.protection;

import java.time.Instant;

public class RateLimitExceededException extends RuntimeException {

    private final ClientId clientId;
    private final String accountReference;
    private final Instant retryAfter;

    public RateLimitExceededException(ClientId clientId, String accountReference, Instant retryAfter) {
        super("rate limit exceeded for account");
        this.clientId = clientId;
        this.accountReference = accountReference;
        this.retryAfter = retryAfter;
    }

    public ClientId clientId() {
        return clientId;
    }

    public String accountReference() {
        return accountReference;
    }

    public Instant retryAfter() {
        return retryAfter;
    }
}
