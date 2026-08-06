package io.github.viniciusssantos.accountshield.outbox.internal;

import io.github.viniciusssantos.accountshield.outbox.OutboxInvestigationQuery;
import io.github.viniciusssantos.accountshield.outbox.OutboxInvestigationQuery.OutboxInvestigationView;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventEntity;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventRepository;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JpaOutboxInvestigationQuery implements OutboxInvestigationQuery {

    private static final String PROTECTION_DECISION_AGGREGATE = "ProtectionDecision";

    private final OutboxEventRepository repository;

    public JpaOutboxInvestigationQuery(OutboxEventRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OutboxInvestigationView> findByDecisionReference(String decisionReference) {
        Objects.requireNonNull(decisionReference, "decisionReference must not be null");
        if (decisionReference.isBlank() || decisionReference.length() > 128) {
            throw new IllegalArgumentException("decisionReference must contain between 1 and 128 characters");
        }
        return repository
                .findByAggregateTypeAndAggregateIdOrderByOccurredAtAscIdAsc(
                        PROTECTION_DECISION_AGGREGATE, decisionReference)
                .stream()
                .map(this::toView)
                .toList();
    }

    private OutboxInvestigationView toView(OutboxEventEntity entity) {
        return new OutboxInvestigationView(
                entity.getId().toString(),
                entity.getEventType(),
                entity.getStatus(),
                entity.getOccurredAt(),
                entity.getPublishedAt(),
                entity.getDeadLetteredAt(),
                entity.getAttemptCount());
    }
}
