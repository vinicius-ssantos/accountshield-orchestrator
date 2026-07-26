package io.github.viniciusssantos.accountshield.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record IntegrationEventEnvelope(
        UUID eventId,
        String schemaVersion,
        String correlationId,
        Instant occurredAt,
        Object data) {

    public IntegrationEventEnvelope {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(schemaVersion, "schemaVersion must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(data, "data must not be null");
    }
}
