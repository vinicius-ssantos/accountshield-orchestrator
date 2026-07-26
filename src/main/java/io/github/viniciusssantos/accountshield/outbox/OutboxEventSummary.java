package io.github.viniciusssantos.accountshield.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEventSummary(
        UUID id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String status,
        int attemptCount,
        Instant occurredAt,
        Instant publishedAt,
        Instant deadLetteredAt) {
}
