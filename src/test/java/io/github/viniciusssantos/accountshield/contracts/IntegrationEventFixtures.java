package io.github.viniciusssantos.accountshield.contracts;

import io.github.viniciusssantos.accountshield.audit.AuditChainBreak;
import io.github.viniciusssantos.accountshield.audit.AuditChainIntegrityFailed;
import io.github.viniciusssantos.accountshield.challenge.ChallengeCompleted;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.outbox.AccountPseudonymizer;
import io.github.viniciusssantos.accountshield.outbox.IntegrationEventEnvelope;
import io.github.viniciusssantos.accountshield.outbox.IntegrationEventSchema;
import io.github.viniciusssantos.accountshield.policy.PolicyActivated;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionMade;
import io.github.viniciusssantos.accountshield.recovery.RecoveryCompleted;
import io.github.viniciusssantos.accountshield.recovery.RecoveryManualReviewRequired;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * Fixed, deterministic wire-shape fixtures for every outbox integration event type, mirroring
 * {@code outbox.internal.OutboxEventRecorder}'s exact transform (pseudonymize and drop
 * {@code accountReference} for the four event types that carry one; pass the raw record through
 * unchanged for the two that don't) so contract tests exercise the same shape a real webhook
 * delivery actually sends. If that transform ever changes, this class's per-event-type methods
 * must be updated to match -- a deliberate, lighter-weight coupling than intercepting production's
 * actual publish path, traded off for fixture simplicity and test speed (see ADR 0029).
 */
public final class IntegrationEventFixtures {

    public static final UUID FIXED_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    private static final String FIXED_ACCOUNT_REFERENCE = "user@example.com";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final AccountPseudonymizer PSEUDONYMIZER =
            new AccountPseudonymizer("accountshield-local-only-pseudonym-secret");

    private IntegrationEventFixtures() {
    }

    public static IntegrationEventEnvelope protectionDecisionMade() {
        ProtectionDecisionMade event = new ProtectionDecisionMade(
                fixedUuid(2), fixedUuid(3), FIXED_ACCOUNT_REFERENCE, "ALLOW", 10,
                "account-protection-default", "1.0.0", FIXED_INSTANT, false, null, "default", null, null);
        return envelope(event.decisionId().toString(), pseudonymizedPayload(event));
    }

    public static IntegrationEventEnvelope challengeCompleted() {
        ChallengeCompleted event = new ChallengeCompleted(
                fixedUuid(4), FIXED_ACCOUNT_REFERENCE, ChallengeType.TOTP_SIMULATED,
                ChallengeStatus.VERIFIED, FIXED_INSTANT);
        return envelope(event.challengeId().toString(), pseudonymizedPayload(event));
    }

    public static IntegrationEventEnvelope policyActivated() {
        PolicyActivated event = new PolicyActivated("account-protection-default", "1.0.0", FIXED_INSTANT);
        return envelope(event.policyKey(), asMap(event));
    }

    public static IntegrationEventEnvelope recoveryCompleted() {
        RecoveryCompleted event = new RecoveryCompleted(fixedUuid(5), FIXED_ACCOUNT_REFERENCE, "LOGIN", FIXED_INSTANT);
        return envelope(event.recoveryId().toString(), pseudonymizedPayload(event));
    }

    public static IntegrationEventEnvelope recoveryManualReviewRequired() {
        RecoveryManualReviewRequired event = new RecoveryManualReviewRequired(
                fixedUuid(6), FIXED_ACCOUNT_REFERENCE, "MANUAL_REVIEW", FIXED_INSTANT);
        return envelope(event.recoveryId().toString(), pseudonymizedPayload(event));
    }

    public static IntegrationEventEnvelope auditChainIntegrityFailed() {
        AuditChainIntegrityFailed event = new AuditChainIntegrityFailed(
                10L, 20L, List.of(new AuditChainBreak(15, "record_hash does not match recomputed content")),
                FIXED_INSTANT);
        return envelope(event.fromSequence() + "-" + event.toSequence(), asMap(event));
    }

    /** Event-type name -> fixture envelope, for tests that need to iterate all six. */
    public static Map<String, IntegrationEventEnvelope> all() {
        return Map.of(
                "PROTECTION_DECISION_MADE", protectionDecisionMade(),
                "CHALLENGE_COMPLETED", challengeCompleted(),
                "POLICY_ACTIVATED", policyActivated(),
                "RECOVERY_COMPLETED", recoveryCompleted(),
                "RECOVERY_MANUAL_REVIEW_REQUIRED", recoveryManualReviewRequired(),
                "AUDIT_INTEGRITY_FAILED", auditChainIntegrityFailed());
    }

    public static ObjectMapper objectMapper() {
        return OBJECT_MAPPER;
    }

    private static IntegrationEventEnvelope envelope(String correlationId, Object data) {
        return new IntegrationEventEnvelope(
                FIXED_EVENT_ID, IntegrationEventSchema.CURRENT_VERSION, correlationId, FIXED_INSTANT, data);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pseudonymizedPayload(Object event) {
        Map<String, Object> payload = (Map<String, Object>) OBJECT_MAPPER.convertValue(event, Map.class);
        payload.remove("accountReference");
        payload.put("subjectToken", PSEUDONYMIZER.pseudonymize(FIXED_ACCOUNT_REFERENCE));
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object event) {
        return (Map<String, Object>) OBJECT_MAPPER.convertValue(event, Map.class);
    }

    private static UUID fixedUuid(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }
}
