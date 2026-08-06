package io.github.viniciusssantos.accountshield.outbox;

import java.util.UUID;

public final class OutboxEventNotDeadLetteredException extends RuntimeException {

    private final UUID eventId;
    private final String currentStatus;

    public OutboxEventNotDeadLetteredException(UUID eventId, String currentStatus) {
        super("outbox event " + eventId + " is not dead-lettered (status: " + currentStatus
                + ") and cannot be requeued");
        this.eventId = eventId;
        this.currentStatus = currentStatus;
    }

    public UUID eventId() {
        return eventId;
    }

    public String currentStatus() {
        return currentStatus;
    }
}
