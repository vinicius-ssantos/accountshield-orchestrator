package io.github.viniciusssantos.accountshield.protection;

import java.time.Instant;

public interface ProtectionRateLimiter {

    void checkLimit(String accountReference, Instant now);
}
