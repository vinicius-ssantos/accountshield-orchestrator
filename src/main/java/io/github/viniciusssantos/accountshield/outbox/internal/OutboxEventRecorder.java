package io.github.viniciusssantos.accountshield.outbox.internal;

import io.github.viniciusssantos.accountshield.challenge.ChallengeCompleted;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventEntity;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventRepository;
import io.github.viniciusssantos.accountshield.policy.PolicyActivated;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionMade;
import io.github.viniciusssantos.accountshield.recovery.RecoveryCompleted;
import java.time.Instant;
import java.util.Map;
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
    private final AccountPseudonymizer pseudonymizer;

    public OutboxEventRecorder(
            OutboxEventRepository repository, ObjectMapper objectMapper, AccountPseudonymizer pseudonymizer) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.pseudonymizer = pseudonymizer;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onProtectionDecisionMade(ProtectionDecisionMade event) {
        record(event.decidedAt(), "ProtectionDecision", event.decisionId().toString(),
                "PROTECTION_DECISION_MADE", pseudonymizedPayload(event, event.accountReference()));
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onChallengeCompleted(ChallengeCompleted event) {
        record(event.completedAt(), "Challenge", event.challengeId().toString(),
                "CHALLENGE_COMPLETED", pseudonymizedPayload(event, event.accountReference()));
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
                "RECOVERY_COMPLETED", pseudonymizedPayload(event, event.accountReference()));
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

    // the outbox payload is the actual integration-event boundary; raw account identifiers must not cross it
    @SuppressWarnings("unchecked")
    private Map<String, Object> pseudonymizedPayload(Object event, String accountReference) {
        Map<String, Object> payload = (Map<String, Object>) objectMapper.convertValue(event, Map.class);
        payload.remove("accountReference");
        payload.put("subjectToken", pseudonymizer.pseudonymize(accountReference));
        return payload;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("failed to serialize outbox event payload", exception);
        }
    }
}
