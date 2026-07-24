package io.github.viniciusssantos.accountshield.protection.internal;

import io.github.viniciusssantos.accountshield.audit.DecisionReasonContribution;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceCommand;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceRecorder;
import io.github.viniciusssantos.accountshield.challenge.ChallengePlan;
import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.challenge.CreateChallengeCommand;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluation;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationContext;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationService;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.protection.ConflictingIdempotencyRequestException;
import io.github.viniciusssantos.accountshield.protection.IdempotencyGuard;
import io.github.viniciusssantos.accountshield.protection.IdempotencyResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionMade;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.protection.ProtectionRateLimiter;
import io.github.viniciusssantos.accountshield.protection.internal.persistence.ProtectionRequestEntity;
import io.github.viniciusssantos.accountshield.protection.internal.persistence.ProtectionRequestRepository;
import io.github.viniciusssantos.accountshield.risk.RiskAssessment;
import io.github.viniciusssantos.accountshield.risk.RiskAssessmentService;
import io.github.viniciusssantos.accountshield.risk.RiskReason;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProtectionDecisionApplicationService implements ProtectionDecisionService {

    private static final String DEFAULT_POLICY_KEY = "account-protection-default";
    private static final String DECIDED_STATUS = "DECIDED";

    private final RiskAssessmentService riskAssessmentService;
    private final PolicyEvaluationService policyEvaluationService;
    private final ProtectionRequestRepository protectionRequestRepository;
    private final DecisionTraceRecorder decisionTraceRecorder;
    private final IdempotencyGuard idempotencyGuard;
    private final ChallengeService challengeService;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ProtectionRateLimiter rateLimiter;

    public ProtectionDecisionApplicationService(
            RiskAssessmentService riskAssessmentService,
            PolicyEvaluationService policyEvaluationService,
            ProtectionRequestRepository protectionRequestRepository,
            DecisionTraceRecorder decisionTraceRecorder,
            IdempotencyGuard idempotencyGuard,
            ChallengeService challengeService,
            Clock clock,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher,
            ProtectionRateLimiter rateLimiter) {
        this.riskAssessmentService = riskAssessmentService;
        this.policyEvaluationService = policyEvaluationService;
        this.protectionRequestRepository = protectionRequestRepository;
        this.decisionTraceRecorder = decisionTraceRecorder;
        this.idempotencyGuard = idempotencyGuard;
        this.challengeService = challengeService;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.rateLimiter = rateLimiter;
    }

    @Override
    @Transactional
    public ProtectionDecisionResult decide(ProtectionDecisionCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        Instant now = clock.instant();
        rateLimiter.checkLimit(command.accountReference(), now);
        String requestFingerprint = fingerprint(command);
        String idempotencyKey = resolveIdempotencyKey(command, requestFingerprint);

        IdempotencyResult existing = idempotencyGuard.resolve(idempotencyKey, requestFingerprint, now);
        if (existing.duplicate()) {
            return restoreDecision(existing);
        }

        UUID protectionRequestId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();

        RiskAssessment assessment = riskAssessmentService.assess(command.signals());
        PolicyEvaluation evaluation = command.eventType().recoveryRequest()
                ? policyEvaluationService.evaluate(
                        DEFAULT_POLICY_KEY,
                        assessment.score(),
                        PolicyEvaluationContext.recoveryRequestContext())
                : policyEvaluationService.evaluate(DEFAULT_POLICY_KEY, assessment.score());

        protectionRequestRepository.save(new ProtectionRequestEntity(
                protectionRequestId,
                command.accountReference(),
                command.eventType().name(),
                requestFingerprint,
                DECIDED_STATUS,
                now));

        decisionTraceRecorder.record(new DecisionTraceCommand(
                decisionId,
                protectionRequestId,
                command.accountReference(),
                requestFingerprint,
                assessment.algorithmVersion(),
                evaluation.policyKey(),
                evaluation.policyVersion(),
                evaluation.outcome().name(),
                assessment.score(),
                normalizedContext(command),
                now,
                auditReasons(assessment.reasons())));

        ChallengePlan challenge = null;
        if (evaluation.outcome() == ProtectionOutcome.REQUIRE_STEP_UP) {
            challenge = challengeService.create(new CreateChallengeCommand(
                    command.accountReference(),
                    ChallengeType.TOTP_SIMULATED,
                    ChallengePurpose.PROTECTION_STEP_UP,
                    protectionRequestId));
        }

        ProtectionDecisionResult result = new ProtectionDecisionResult(
                decisionId,
                protectionRequestId,
                evaluation.outcome(),
                assessment.score(),
                assessment.band(),
                assessment.algorithmVersion(),
                evaluation.policyKey(),
                evaluation.policyVersion(),
                assessment.reasons(),
                now,
                challenge);

        idempotencyGuard.record(
                idempotencyKey,
                requestFingerprint,
                idempotencyGuard instanceof DatabaseIdempotencyGuard dbg ? dbg.resourceType() : "protection_decision",
                protectionRequestId,
                serializeResult(result),
                now,
                now.plus(java.time.Duration.ofHours(24)));

        eventPublisher.publishEvent(new ProtectionDecisionMade(
                decisionId,
                protectionRequestId,
                command.accountReference(),
                evaluation.outcome().name(),
                assessment.score(),
                evaluation.policyKey(),
                evaluation.policyVersion(),
                now));

        return result;
    }

    private String resolveIdempotencyKey(ProtectionDecisionCommand command, String requestFingerprint) {
        if (command.idempotencyKey() != null) {
            return command.idempotencyKey();
        }
        return UUID.randomUUID().toString();
    }

    private ProtectionDecisionResult restoreDecision(IdempotencyResult existing) {
        try {
            return objectMapper.readValue(
                    existing.responsePayload(),
                    ProtectionDecisionResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to restore idempotent decision", e);
        }
    }

    private String serializeResult(ProtectionDecisionResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize decision for idempotency", e);
        }
    }

    private Map<String, Object> normalizedContext(ProtectionDecisionCommand command) {
        RiskSignals signals = command.signals();
        return Map.of(
                "failedAttempts", signals.failedAttempts(),
                "newDevice", signals.newDevice(),
                "impossibleTravel", signals.impossibleTravel(),
                "compromisedCredential", signals.compromisedCredential(),
                "networkRiskLevel", signals.networkRiskLevel().name(),
                "protectionEventType", command.eventType().name(),
                "recoveryRequest", command.eventType().recoveryRequest());
    }

    private List<DecisionReasonContribution> auditReasons(List<RiskReason> reasons) {
        return reasons.stream()
                .map(reason -> new DecisionReasonContribution(reason.code(), reason.contribution(), Map.of()))
                .toList();
    }

    private String fingerprint(ProtectionDecisionCommand command) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF(command.accountReference());
                output.writeUTF(command.eventType().name());
                output.writeInt(command.signals().failedAttempts());
                output.writeBoolean(command.signals().newDevice());
                output.writeBoolean(command.signals().impossibleTravel());
                output.writeBoolean(command.signals().compromisedCredential());
                output.writeUTF(command.signals().networkRiskLevel().name());
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("failed to compute request fingerprint", exception);
        }
    }
}
