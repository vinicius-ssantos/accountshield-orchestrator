package io.github.viniciusssantos.accountshield.outbox.internal;

import io.github.viniciusssantos.accountshield.outbox.OutboxEventPublisher;
import io.github.viniciusssantos.accountshield.outbox.OutboxMessage;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventEntity;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventRepository;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 1000;

    private final OutboxEventRepository repository;
    private final OutboxEventPublisher publisher;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(
            OutboxEventRepository repository,
            OutboxEventPublisher publisher,
            @Qualifier("decisionClock") Clock clock,
            @Value("${accountshield.outbox.relay.batch-size:50}") int batchSize,
            @Value("${accountshield.outbox.relay.max-attempts:5}") int maxAttempts) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${accountshield.outbox.relay.fixed-delay:5s}")
    public void dispatchPending() {
        List<OutboxEventEntity> pending = repository.findUnpublished(PageRequest.of(0, batchSize));
        for (OutboxEventEntity event : pending) {
            dispatchSingle(event);
        }
    }

    private void dispatchSingle(OutboxEventEntity event) {
        if (event.getAttemptCount() >= maxAttempts) {
            return;
        }
        try {
            publisher.publish(toMessage(event));
            event.markPublished(clock.instant());
        } catch (Exception ex) {
            event.recordFailure(boundError(ex), clock.instant());
            log.warn(
                    "outbox_publish_failed event_id={} attempt={} error_class={}: {}",
                    event.getId(),
                    event.getAttemptCount(),
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
        }
        saveWithConflictHandling(event);
    }

    private void saveWithConflictHandling(OutboxEventEntity event) {
        try {
            repository.save(event);
        } catch (OptimisticLockingFailureException ex) {
            log.debug(
                    "outbox_publish_conflict event_id={} version={} — another instance handled this event",
                    event.getId(),
                    event.getAttemptCount());
        }
    }

    private OutboxMessage toMessage(OutboxEventEntity entity) {
        return new OutboxMessage(
                entity.getId(),
                entity.getAggregateType(),
                entity.getAggregateId(),
                entity.getEventType(),
                entity.getPayload(),
                entity.getOccurredAt());
    }

    private String boundError(Exception ex) {
        String message = Objects.requireNonNullElse(ex.getMessage(), ex.getClass().getSimpleName());
        return message.length() > MAX_ERROR_LENGTH
                ? message.substring(0, MAX_ERROR_LENGTH)
                : message;
    }
}
