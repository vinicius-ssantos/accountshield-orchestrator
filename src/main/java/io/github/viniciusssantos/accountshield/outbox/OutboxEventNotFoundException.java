package io.github.viniciusssantos.accountshield.outbox;

import java.util.UUID;

public final class OutboxEventNotFoundException extends RuntimeException {

    private final UUID eventId;

    public OutboxEventNotFoundException(UUID eventId) {
        super("outbox event not found: " + eventId);
        this.eventId = eventId;
    }

    public UUID eventId() {
        return eventId;
    }
}
