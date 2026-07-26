package io.github.viniciusssantos.accountshield.outbox.internal;

import java.time.Instant;
import java.util.UUID;

record ClaimedOutboxEvent(
        UUID id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        Instant occurredAt,
        int attemptCount) {
}
