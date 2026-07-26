package io.github.viniciusssantos.accountshield.outbox;

import java.util.UUID;

public interface OutboxAdminService {

    /**
     * Resets a dead-lettered event to a fresh PENDING state, immediately eligible for the next
     * relay poll. Throws {@link OutboxEventNotDeadLetteredException} for any event not currently
     * DEAD_LETTERED -- requeue is a deliberate, validated reset, not a resume of arbitrary state.
     */
    void requeue(UUID eventId, String actor);
}
