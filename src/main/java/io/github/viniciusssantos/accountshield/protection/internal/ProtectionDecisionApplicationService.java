package io.github.viniciusssantos.accountshield.protection.internal;

import io.github.viniciusssantos.accountshield.audit.DecisionReasonContribution;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceCommand;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceRecorder;
import io.github.viniciusssantos.accountshield.challenge.ChallengePlan;
import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.challenge.CreateChallengeCommand;
import io.github.viniciusssantos.accountshield.policy.ActivePolicyUnavailableException;
import io.github.viniciusssantos.accountshield.policy.CohortAssignment;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluation;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationContext;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationService;
import io.github.viniciusssantos.accountshield.policy.PolicyRollout;
import io.github.viniciusssantos.accountshield.policy.PolicyRolloutService;
import io.github.viniciusssantos.accountshield.policy.PolicyRoutingService;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.protection.DecisionEngineVersion;
import io.github.viniciusssantos.accountshield.protection.DegradationReason;
import io.github.viniciusssantos.accountshield.protection.IdempotencyGuard;
import io.github.viniciusssantos.accountshield.protection.IdempotencyResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionMade;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.protection.ProtectionRateLimiter;
import io.github.viniciusssantos.accountshield.protection.RecoveryAuthorizationIssued;
import io.github.viniciusssantos.accountshield.protection.RequestFingerprint;
import io.github.viniciusssantos.accountshield.protection.StaleRiskSignalException;
import io.github.viniciusssantos.accountshield.protection.internal.persistence.ProtectionRequestEntity;
import io.github.viniciusssantos.accountshield.protection.internal.persistence.ProtectionRequestRepository;
import io.github.viniciusssantos.accountshield.risk.RiskAssessment;
import io.github.viniciusssantos.accountshield.risk.RiskAssessmentService;
import io.github.viniciusssantos.accountshield.risk.RiskReason;
import io.github.viniciusssantos.accountshield.risk.RiskReasonCatalog;
import io.github.viniciusssantos.accountshield.risk.RiskSignalEnvelope;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProtectionDecisionApplicationService implements ProtectionDecisionService {

    private static final String DECIDED_STATUS = "DECIDED";
    private static final Duration RECOVERY_AUTHORIZATION_TTL = Duration.ofMinutes(15);
    private static final String DECISION_DURATION_METRIC = "accountshield.protection.decision.duration";
    private static final String DECISIONS_FAILED_METRIC = "accountshield.protection.decisions.failed";

    private final RiskAssessmentService riskAssessmentService;
    private final PolicyEvaluationService policyEvaluationService;
    private final PolicyRoutingService policyRoutingService;
    private final PolicyRolloutService policyRolloutService;
    private final ProtectionRequestRepository protectionRequestRepository;
    private final DecisionTraceRecorder decisionTraceRecorder;
    private final IdempotencyGuard idempotencyGuard;
    private final ChallengeService challengeService;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ProtectionRateLimiter rateLimiter;
    private final Duration maxSignalAge;
    private final MeterRegistry meterRegistry;

    public ProtectionDecisionApplicationService(
            RiskAssessmentService riskAssessmentService,
            PolicyEvaluationService policyEvaluationService,
            PolicyRoutingService policyRoutingService,
            PolicyRolloutService policyRolloutService,
            ProtectionRequestRepository protectionRequestRepository,
            DecisionTraceRecorder decisionTraceRecorder,
            IdempotencyGuard idempotencyGuard,
            ChallengeService challengeService,
            Clock clock,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher,
            ProtectionRateLimiter rateLimiter,
            @Value("${accountshield.risk.max-signal-age:5m}") Duration maxSignalAge,
            MeterRegistry meterRegistry) {
        this.riskAssessmentService = riskAssessmentService;
        this.policyEvaluationService = policyEvaluationService;
        this.policyRoutingService = policyRoutingService;
        this.policyRolloutService = policyRolloutService;
        this.protectionRequestRepository = protectionRequestRepository;
        this.decisionTraceRecorder = decisionTraceRecorder;
        this.idempotencyGuard = idempotencyGuard;
        this.challengeService = challengeService;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.rateLimiter = rateLimiter;
        this.maxSignalAge = maxSignalAge;
        this.meterRegistry = meterRegistry;
    }

    @Override
    @Transactional
    public ProtectionDecisionResult decide(ProtectionDecisionCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            ProtectionDecisionResult result = decideInternal(command);
            sample.stop(decisionTimer(result.outcome().name()));
            return result;
        } catch (RuntimeException exception) {
            sample.stop(decisionTimer("ERROR"));
            Counter.builder(DECISIONS_FAILED_METRIC)
                    .description("Total protection decisions that failed before completing")
                    .tag("exception", exception.getClass().getSimpleName())
                    .register(meterRegistry)
                    .increment();
            throw exception;
        }
    }

    // Explicit SLO boundaries (not just the default histogram) so p50/p95/p99 PromQL queries
    // against this metric's Prometheus histogram buckets are meaningful at the latencies this
    // system actually operates at, rather than Micrometer's generic default bucket set.
    private Timer decisionTimer(String outcomeTag) {
        return Timer.builder(DECISION_DURATION_METRIC)
                .description("Protection decision latency")
                .tag("outcome", outcomeTag)
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250),
                        Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2))
                .register(meterRegistry);
    }

    private ProtectionDecisionResult decideInternal(ProtectionDecisionCommand command) {
        Instant now = clock.instant();
        if (command.signalEnvelope().isStale(now, maxSignalAge)) {
            degradedCounter(DegradationReason.RISK_SIGNAL_STALE).increment();
            throw new StaleRiskSignalException(command.signalEnvelope().observedAt());
        }
        rateLimiter.checkLimit(command.clientId(), command.accountReference(), now);
        String requestFingerprint = fingerprint(command);
        String idempotencyKey = resolveIdempotencyKey(command, requestFingerprint);

        UUID protectionRequestId = UUID.randomUUID();
        IdempotencyResult claim = idempotencyGuard.claim(
                command.clientId().value(), idempotencyKey, requestFingerprint, protectionRequestId, now);
        if (claim.duplicate()) {
            return restoreDecision(claim);
        }

        UUID decisionId = UUID.randomUUID();

        RiskAssessment assessment = riskAssessmentService.assess(command.signalEnvelope());
        PolicyEvaluation evaluation;
        RolloutSelection rolloutSelection = null;
        try {
            String policyKey = policyRoutingService.resolvePolicyKey(
                    command.clientId().value(), command.eventType().name());
            boolean recoveryRequest = command.eventType().recoveryRequest();
            Optional<PolicyRollout> rollout = policyRolloutService.findActiveRollout(policyKey);
            String candidateVersion = null;
            if (rollout.isPresent()) {
                PolicyRollout activeRollout = rollout.get();
                int bucket = CohortAssignment.bucket(
                        command.clientId().value(), command.accountReference(), policyKey);
                boolean candidateSelected = bucket < activeRollout.rolloutPercentage();
                rolloutSelection = new RolloutSelection(
                        bucket, activeRollout.candidateVersion(), activeRollout.rolloutPercentage(), candidateSelected);
                candidateVersion = candidateSelected ? activeRollout.candidateVersion() : null;
            }
            if (candidateVersion != null) {
                evaluation = recoveryRequest
                        ? policyEvaluationService.evaluateVersion(
                                policyKey, candidateVersion, assessment.score(),
                                PolicyEvaluationContext.recoveryRequestContext())
                        : policyEvaluationService.evaluateVersion(policyKey, candidateVersion, assessment.score());
            } else {
                evaluation = recoveryRequest
                        ? policyEvaluationService.evaluate(
                                policyKey, assessment.score(), PolicyEvaluationContext.recoveryRequestContext())
                        : policyEvaluationService.evaluate(policyKey, assessment.score());
            }
        } catch (ActivePolicyUnavailableException exception) {
            degradedCounter(DegradationReason.ACTIVE_POLICY_UNAVAILABLE).increment();
            throw exception;
        }

        // flushed immediately: decisionTraceRecorder below issues a raw JDBC insert (not through
        // this JPA session) referencing protection_request_id via a foreign key, so the row must
        // be physically visible to the connection before that insert runs, not just queued in the
        // Hibernate session's write-behind cache
        protectionRequestRepository.saveAndFlush(new ProtectionRequestEntity(
                protectionRequestId,
                command.clientId().value(),
                command.accountReference(),
                command.eventType().name(),
                requestFingerprint,
                DECIDED_STATUS,
                now));

        ProtectionOutcome effectiveOutcome = evaluation.outcome();
        boolean degraded = false;
        String degradationReason = null;

        ChallengePlan challenge = null;
        if (evaluation.outcome() == ProtectionOutcome.REQUIRE_STEP_UP) {
            try {
                challenge = challengeService.create(new CreateChallengeCommand(
                        command.accountReference(),
                        ChallengeType.TOTP_SIMULATED,
                        ChallengePurpose.PROTECTION_STEP_UP,
                        protectionRequestId));
            } catch (RuntimeException exception) {
                effectiveOutcome = ProtectionOutcome.TEMPORARILY_BLOCK;
                degraded = true;
                degradationReason = DegradationReason.CHALLENGE_PROVIDER_UNAVAILABLE.name();
                challenge = null;
            }
        }

        decisionTraceRecorder.record(new DecisionTraceCommand(
                decisionId,
                protectionRequestId,
                command.accountReference(),
                requestFingerprint,
                assessment.algorithmVersion(),
                evaluation.policyKey(),
                evaluation.policyVersion(),
                effectiveOutcome.name(),
                assessment.score(),
                normalizedContext(command, degraded, degradationReason, rolloutSelection),
                now,
                auditReasons(assessment.reasons())));

        UUID recoveryAuthorizationId = null;
        if (effectiveOutcome == ProtectionOutcome.START_RECOVERY) {
            recoveryAuthorizationId = UUID.randomUUID();
            eventPublisher.publishEvent(new RecoveryAuthorizationIssued(
                    recoveryAuthorizationId,
                    protectionRequestId,
                    decisionId,
                    command.accountReference(),
                    recoveryDirective(command.eventType()),
                    assessment.score(),
                    now,
                    now.plus(RECOVERY_AUTHORIZATION_TTL)));
        }

        ProtectionDecisionResult result = new ProtectionDecisionResult(
                decisionId,
                protectionRequestId,
                recoveryAuthorizationId,
                effectiveOutcome,
                assessment.score(),
                assessment.band(),
                assessment.algorithmVersion(),
                evaluation.policyKey(),
                evaluation.policyVersion(),
                assessment.reasons(),
                now,
                challenge,
                degraded,
                degradationReason);

        idempotencyGuard.finalizeResult(command.clientId().value(), idempotencyKey, serializeResult(result));

        eventPublisher.publishEvent(new ProtectionDecisionMade(
                decisionId,
                protectionRequestId,
                command.accountReference(),
                effectiveOutcome.name(),
                assessment.score(),
                evaluation.policyKey(),
                evaluation.policyVersion(),
                now,
                degraded,
                degradationReason,
                command.clientId().value(),
                rolloutSelection == null ? null : rolloutSelection.candidateVersion(),
                rolloutSelection == null ? null : rolloutSelection.candidateSelected()));

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

    private Map<String, Object> normalizedContext(
            ProtectionDecisionCommand command, boolean degraded, String degradationReason,
            RolloutSelection rolloutSelection) {
        RiskSignalEnvelope envelope = command.signalEnvelope();
        RiskSignals signals = envelope.signals();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("failedAttempts", signals.failedAttempts());
        context.put("newDevice", signals.newDevice());
        context.put("impossibleTravel", signals.impossibleTravel());
        context.put("compromisedCredential", signals.compromisedCredential());
        context.put("networkRiskLevel", signals.networkRiskLevel().name());
        context.put("protectionEventType", command.eventType().name());
        context.put("recoveryRequest", command.eventType().recoveryRequest());
        context.put("signalProvider", envelope.provider());
        context.put("signalObservedAt", envelope.observedAt().toString());
        context.put("signalConfidence", envelope.confidence().name());
        context.put("signalSchemaVersion", envelope.schemaVersion());
        context.put("signalSimulated", envelope.simulated());
        context.put("clientId", command.clientId().value());
        context.put("reasonCatalogVersion", RiskReasonCatalog.CURRENT_VERSION);
        context.put("decisionEngineVersion", DecisionEngineVersion.CURRENT);
        context.put("degraded", degraded);
        if (degradationReason != null) {
            context.put("degradationReason", degradationReason);
        }
        if (rolloutSelection != null) {
            context.put("rolloutCohortBucket", rolloutSelection.cohortBucket());
            context.put("rolloutCandidateVersion", rolloutSelection.candidateVersion());
            context.put("rolloutPercentageAtDecision", rolloutSelection.rolloutPercentage());
            context.put("rolloutCandidateSelected", rolloutSelection.candidateSelected());
        }
        return context;
    }

    private Counter degradedCounter(DegradationReason reason) {
        return Counter.builder("accountshield.protection.degraded_decisions")
                .description("Total decisions produced under a dependency-failure degradation strategy")
                .tag("reason", reason.name())
                .register(meterRegistry);
    }

    private record RolloutSelection(
            int cohortBucket, String candidateVersion, int rolloutPercentage, boolean candidateSelected) {
    }

    private String recoveryDirective(ProtectionEventType eventType) {
        return switch (eventType) {
            case LOGIN_RECOVERY_ATTEMPT -> "LOGIN";
            case PASSWORD_RESET_ATTEMPT -> "PASSWORD_RESET";
            case CREDENTIAL_CHANGE_ATTEMPT -> "CREDENTIAL_CHANGE";
            case DEVICE_TRUST_RESET_ATTEMPT -> "DEVICE_TRUST_RESET";
            default -> throw new IllegalStateException(
                    "START_RECOVERY requires a recovery-request event type");
        };
    }

    private List<DecisionReasonContribution> auditReasons(List<RiskReason> reasons) {
        return reasons.stream()
                .map(reason -> new DecisionReasonContribution(reason.code(), reason.contribution(), Map.of()))
                .toList();
    }

    private String fingerprint(ProtectionDecisionCommand command) {
        RiskSignals signals = command.signalEnvelope().signals();
        return RequestFingerprint.compute(
                command.clientId().value(),
                command.accountReference(),
                command.eventType().name(),
                signals.failedAttempts(),
                signals.newDevice(),
                signals.impossibleTravel(),
                signals.compromisedCredential(),
                signals.networkRiskLevel().name());
    }
}
