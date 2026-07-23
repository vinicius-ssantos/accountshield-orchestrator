package io.github.viniciusssantos.accountshield.outbox.internal;

import io.github.viniciusssantos.accountshield.outbox.OutboxEventPublisher;
import io.github.viniciusssantos.accountshield.outbox.OutboxMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingOutboxEventPublisher implements OutboxEventPublisher {

    private static final Logger log = LoggerFactory.getLogger("accountshield.outbox");

    @Override
    public void publish(OutboxMessage message) {
        log.info(
                "outbox_published event_type={} aggregate_type={} aggregate_id={} occurred_at={}",
                message.eventType(),
                message.aggregateType(),
                message.aggregateId(),
                message.occurredAt());
    }
}
