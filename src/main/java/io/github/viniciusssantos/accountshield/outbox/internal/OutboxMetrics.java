package io.github.viniciusssantos.accountshield.outbox.internal;

import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Point-in-time outbox health signals for external alerting: how many events are stuck in the
 * dead-letter state and how stale the oldest still-pending event is.
 */
@Component
class OutboxMetrics {

    OutboxMetrics(
            OutboxEventRepository repository,
            @Qualifier("decisionClock") Clock clock,
            MeterRegistry meterRegistry) {
        Gauge.builder("accountshield.outbox.dead_lettered.count", repository,
                        r -> (double) r.countByStatus("DEAD_LETTERED"))
                .description("Current number of dead-lettered outbox events")
                .register(meterRegistry);
        Gauge.builder("accountshield.outbox.pending.oldest_age_seconds", repository,
                        r -> oldestPendingAgeSeconds(r, clock))
                .description("Age in seconds of the oldest still-pending outbox event, 0 if none")
                .register(meterRegistry);
    }

    private static double oldestPendingAgeSeconds(OutboxEventRepository repository, Clock clock) {
        return repository.findOldestPendingOccurredAt()
                .map(occurredAt -> (double) Duration.between(occurredAt, clock.instant()).toSeconds())
                .orElse(0.0);
    }
}
