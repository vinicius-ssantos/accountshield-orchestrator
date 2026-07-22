package io.github.viniciusssantos.accountshield.outbox.internal;

import io.github.viniciusssantos.accountshield.challenge.ChallengeCompleted;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventEntity;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventRepository;
import io.github.viniciusssantos.accountshield.policy.PolicyActivated;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionMade;
import io.github.viniciusssantos.accountshield.recovery.RecoveryCompleted;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
public class OutboxEventRecorder {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxEventRecorder(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onProtectionDecisionMade(ProtectionDecisionMade event) {
        record(event.decidedAt(), "ProtectionDecision", event.decisionId().toString(),
                "PROTECTION_DECISION_MADE", event);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onChallengeCompleted(ChallengeCompleted event) {
        record(event.completedAt(), "Challenge", event.challengeId().toString(),
                "CHALLENGE_COMPLETED", event);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPolicyActivated(PolicyActivated event) {
        record(event.activatedAt(), "Policy", event.policyKey(),
                "POLICY_ACTIVATED", event);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onRecoveryCompleted(RecoveryCompleted event) {
        record(event.completedAt(), "Recovery", event.recoveryId().toString(),
                "RECOVERY_COMPLETED", event);
    }

    private void record(Instant occurredAt, String aggregateType, String aggregateId,
            String eventType, Object payload) {
        repository.save(new OutboxEventEntity(
                UUID.randomUUID(),
                aggregateType,
                aggregateId,
                eventType,
                serialize(payload),
                occurredAt));
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("failed to serialize outbox event payload", exception);
        }
    }
}
