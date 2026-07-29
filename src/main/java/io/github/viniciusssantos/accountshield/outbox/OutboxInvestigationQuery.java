package io.github.viniciusssantos.accountshield.outbox;

import java.time.Instant;
import java.util.List;

/** Read-only, payload-free outbox projection for operator investigation. */
public interface OutboxInvestigationQuery {

    List<OutboxInvestigationView> findByDecisionReference(String decisionReference);

    record OutboxInvestigationView(
            String reference,
            String eventType,
            String status,
            Instant occurredAt,
            Instant publishedAt,
            Instant deadLetteredAt,
            int attemptCount) {
    }
}
