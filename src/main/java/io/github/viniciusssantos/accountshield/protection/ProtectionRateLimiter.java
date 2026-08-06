package io.github.viniciusssantos.accountshield.protection;

import java.time.Instant;

public interface ProtectionRateLimiter {

    void checkLimit(ClientId clientId, String accountReference, Instant now);
}
