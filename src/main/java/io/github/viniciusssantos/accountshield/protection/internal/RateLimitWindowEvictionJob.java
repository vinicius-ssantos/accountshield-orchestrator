package io.github.viniciusssantos.accountshield.protection.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically evicts {@link InMemorySlidingWindowRateLimiter}'s stale, empty window entries
 * (issue #149 / F-21): {@code checkLimit} alone never leaves an abandoned key's entry behind for
 * eviction on next access, since every completed call leaves at least one timestamp in the deque
 * it just touched. Without this job the map grows without bound for the life of the process as
 * distinct {@code (clientId, accountReference)} pairs are seen once and never again.
 */
@Component
class RateLimitWindowEvictionJob {

    private final InMemorySlidingWindowRateLimiter rateLimiter;
    private final Clock clock;

    RateLimitWindowEvictionJob(
            InMemorySlidingWindowRateLimiter rateLimiter,
            @Qualifier("decisionClock") Clock clock,
            MeterRegistry meterRegistry) {
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        Gauge.builder("accountshield.protection.rate-limit.tracked-windows", rateLimiter,
                        InMemorySlidingWindowRateLimiter::trackedWindowCount)
                .description("Distinct (client, account reference) rate-limit windows currently tracked in memory")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${accountshield.protection.rate-limit.eviction-interval:5m}")
    void evictStaleWindows() {
        rateLimiter.evictExpiredWindows(clock.instant());
    }
}
