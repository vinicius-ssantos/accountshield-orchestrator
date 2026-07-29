package io.github.viniciusssantos.accountshield.protection.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimitWindowEvictionJobTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-07-29T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    @Test
    void evictStaleWindowsDelegatesToTheRateLimiterWithTheCurrentInstant() {
        InMemorySlidingWindowRateLimiter rateLimiter = mock(InMemorySlidingWindowRateLimiter.class);
        RateLimitWindowEvictionJob job =
                new RateLimitWindowEvictionJob(rateLimiter, FIXED_CLOCK, new SimpleMeterRegistry());

        job.evictStaleWindows();

        verify(rateLimiter).evictExpiredWindows(FIXED_INSTANT);
    }
}
