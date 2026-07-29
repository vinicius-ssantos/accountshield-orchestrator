package io.github.viniciusssantos.accountshield.outbox.internal;

import io.github.viniciusssantos.accountshield.outbox.OutboxEventPublisher;
import io.github.viniciusssantos.accountshield.outbox.OutboxMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final String DISPATCH_METRIC = "accountshield.outbox.relay.dispatch";

    private final OutboxClaimStore claimStore;
    private final OutboxEventPublisher publisher;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final OutboxBackoffCalculator backoffCalculator;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration claimTimeout;
    private final String instanceId;

    public OutboxRelay(
            OutboxClaimStore claimStore,
            OutboxEventPublisher publisher,
            @Qualifier("decisionClock") Clock clock,
            MeterRegistry meterRegistry,
            @Value("${accountshield.outbox.relay.batch-size:50}") int batchSize,
            @Value("${accountshield.outbox.relay.max-attempts:5}") int maxAttempts,
            @Value("${accountshield.outbox.relay.claim-timeout:2m}") Duration claimTimeout,
            @Value("${accountshield.outbox.relay.backoff.base-delay:1s}") Duration backoffBaseDelay,
            @Value("${accountshield.outbox.relay.backoff.max-delay:5m}") Duration backoffMaxDelay) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.claimStore = claimStore;
        this.publisher = publisher;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.backoffCalculator = new OutboxBackoffCalculator(backoffBaseDelay, backoffMaxDelay);
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.claimTimeout = claimTimeout;
        this.instanceId = generateInstanceId();
    }

    @Scheduled(fixedDelayString = "${accountshield.outbox.relay.fixed-delay:5s}")
    public void dispatchPending() {
        Instant now = clock.instant();
        Instant staleClaimCutoff = now.minus(claimTimeout);
        List<ClaimedOutboxEvent> claimed = claimStore.claimBatch(now, staleClaimCutoff, instanceId, batchSize);
        for (ClaimedOutboxEvent event : claimed) {
            dispatchSingle(event);
        }
    }

    private void dispatchSingle(ClaimedOutboxEvent event) {
        try {
            publisher.publish(toMessage(event));
            boolean acked = claimStore.markPublished(event.id(), event.claimToken(), clock.instant());
            recordAckOutcome(event.id(), "published", acked);
        } catch (Exception ex) {
            int newAttemptCount = event.attemptCount() + 1;
            String error = boundError(ex);
            if (newAttemptCount >= maxAttempts) {
                boolean acked = claimStore.markDeadLettered(event.id(), event.claimToken(), newAttemptCount, error, clock.instant());
                recordAckOutcome(event.id(), "dead_lettered", acked);
                if (acked) {
                    log.warn(
                            "outbox_dead_lettered event_id={} attempts={} error_class={}: {}",
                            event.id(), newAttemptCount, ex.getClass().getSimpleName(), ex.getMessage());
                }
            } else {
                Instant nextAttemptAt = backoffCalculator.nextAttemptAt(clock.instant(), newAttemptCount);
                boolean acked = claimStore.markFailedWithBackoff(
                        event.id(), event.claimToken(), newAttemptCount, error, nextAttemptAt);
                recordAckOutcome(event.id(), "failed", acked);
                if (acked) {
                    log.warn(
                            "outbox_publish_failed event_id={} attempt={} next_attempt_at={} error_class={}: {}",
                            event.id(), newAttemptCount, nextAttemptAt, ex.getClass().getSimpleName(), ex.getMessage());
                }
            }
        }
    }

    /**
     * A {@code false} outcome means this worker's claim was already superseded by a newer owner
     * (issue #145 / F-18) -- the event was already handled under a different claim, so this ack
     * is a no-op, not an error: log at info rather than warn, and tag the metric distinctly so a
     * sustained rate of stale acks (a symptom of claim timeouts set too aggressively relative to
     * publish latency) is observable without being confused with genuine dispatch outcomes.
     */
    private void recordAckOutcome(UUID eventId, String outcome, boolean acked) {
        if (acked) {
            increment(outcome);
            return;
        }
        increment("stale_ack");
        log.info("outbox_stale_ack event_id={} attempted_outcome={} -- claim already superseded", eventId, outcome);
    }

    private OutboxMessage toMessage(ClaimedOutboxEvent event) {
        return new OutboxMessage(
                event.id(),
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                event.payload(),
                event.occurredAt());
    }

    private String boundError(Exception ex) {
        String message = Objects.requireNonNullElse(ex.getMessage(), ex.getClass().getSimpleName());
        return message.length() > MAX_ERROR_LENGTH
                ? message.substring(0, MAX_ERROR_LENGTH)
                : message;
    }

    private void increment(String outcome) {
        Counter.builder(DISPATCH_METRIC)
                .description("Total outbox dispatch outcomes")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    private static String generateInstanceId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
        } catch (UnknownHostException ex) {
            return "relay-" + UUID.randomUUID();
        }
    }
}
