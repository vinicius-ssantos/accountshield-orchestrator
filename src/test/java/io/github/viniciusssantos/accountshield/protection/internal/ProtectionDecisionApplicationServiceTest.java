package io.github.viniciusssantos.accountshield.protection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.audit.DecisionTraceCommand;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceRecorder;
import io.github.viniciusssantos.accountshield.challenge.ChallengePlan;
import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.challenge.CreateChallengeCommand;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluation;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationService;
import io.github.viniciusssantos.accountshield.policy.PolicyRoutingService;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.protection.ClientId;
import io.github.viniciusssantos.accountshield.protection.IdempotencyGuard;
import io.github.viniciusssantos.accountshield.protection.IdempotencyResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.protection.ProtectionRateLimiter;
import io.github.viniciusssantos.accountshield.protection.internal.persistence.ProtectionRequestRepository;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskAssessment;
import io.github.viniciusssantos.accountshield.risk.RiskAssessmentService;
import io.github.viniciusssantos.accountshield.risk.RiskBand;
import io.github.viniciusssantos.accountshield.risk.RiskReason;
import io.github.viniciusssantos.accountshield.risk.RiskSignalEnvelope;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import io.github.viniciusssantos.accountshield.risk.SignalConfidence;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

class ProtectionDecisionApplicationServiceTest {

    private final RiskAssessmentService riskAssessmentService = mock(RiskAssessmentService.class);
    private final PolicyEvaluationService policyEvaluationService = mock(PolicyEvaluationService.class);
    private final PolicyRoutingService policyRoutingService =
            (clientId, eventType) -> "account-protection-default";
    private final ProtectionRequestRepository protectionRequestRepository = mock(ProtectionRequestRepository.class);
    private final DecisionTraceRecorder decisionTraceRecorder = mock(DecisionTraceRecorder.class);
    private final IdempotencyGuard idempotencyGuard = mock(IdempotencyGuard.class);
    private final ChallengeService challengeService = mock(ChallengeService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-20T03:00:00Z"), ZoneOffset.UTC);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ProtectionRateLimiter rateLimiter = (clientId, accountReference, now) -> {};
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final ProtectionDecisionApplicationService service = new ProtectionDecisionApplicationService(
            riskAssessmentService,
            policyEvaluationService,
            policyRoutingService,
            protectionRequestRepository,
            decisionTraceRecorder,
            idempotencyGuard,
            challengeService,
            clock,
            new ObjectMapper(),
            eventPublisher,
            rateLimiter,
            Duration.ofMinutes(5),
            meterRegistry);

    @Test
    void persistsAndReturnsTheSameExplainableDecision() {
        RiskSignals signals = new RiskSignals(5, true, false, false, NetworkRiskLevel.LOW);
        RiskSignalEnvelope envelope = new RiskSignalEnvelope(
                signals, "CLIENT_SUPPLIED", Instant.parse("2026-07-20T03:00:00Z"), SignalConfidence.HIGH, null, true);
        RiskAssessment assessment = new RiskAssessment(
                30,
                RiskBand.MEDIUM,
                "risk-rules-1.0",
                List.of(new RiskReason("FAILED_ATTEMPTS", 15), new RiskReason("NEW_DEVICE", 15)));
        when(riskAssessmentService.assess(envelope)).thenReturn(assessment);
        when(policyEvaluationService.evaluate("account-protection-default", 30))
                .thenReturn(new PolicyEvaluation(
                        "account-protection-default",
                        "1.0.0",
                        ProtectionOutcome.REQUIRE_STEP_UP));
        when(idempotencyGuard.claim(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(IdempotencyResult.absent());
        when(challengeService.create(any(CreateChallengeCommand.class)))
                .thenAnswer(invocation -> {
                    CreateChallengeCommand create = invocation.getArgument(0);
                    return new ChallengePlan(
                            java.util.UUID.randomUUID(),
                            create.accountReference(),
                            create.challengeType(),
                            create.purpose(),
                            create.contextId(),
                            io.github.viniciusssantos.accountshield.challenge.ChallengeStatus.CHALLENGED,
                            3,
                            3,
                            Instant.parse("2026-07-20T03:00:00Z"),
                            Instant.parse("2026-07-20T03:10:00Z"),
                            null);
                });

        var result = service.decide(new ProtectionDecisionCommand(
                "account-opaque-123",
                ProtectionEventType.LOGIN_ATTEMPT,
                envelope,
                null));

        assertThat(result.outcome()).isEqualTo(ProtectionOutcome.REQUIRE_STEP_UP);
        assertThat(result.riskScore()).isEqualTo(30);
        assertThat(result.riskBand()).isEqualTo(RiskBand.MEDIUM);
        assertThat(result.policyVersion()).isEqualTo("1.0.0");
        assertThat(result.decidedAt()).isEqualTo(Instant.parse("2026-07-20T03:00:00Z"));
        assertThat(result.challenge()).isNotNull();
        assertThat(result.challenge().challengeType()).isEqualTo(ChallengeType.TOTP_SIMULATED);
        assertThat(result.challenge().purpose()).isEqualTo(ChallengePurpose.PROTECTION_STEP_UP);
        assertThat(result.challenge().contextId()).isEqualTo(result.protectionRequestId());
        verify(protectionRequestRepository).save(any());
        verify(idempotencyGuard).finalizeResult(anyString(), anyString(), anyString());

        ArgumentCaptor<DecisionTraceCommand> traceCaptor = ArgumentCaptor.forClass(DecisionTraceCommand.class);
        verify(decisionTraceRecorder).record(traceCaptor.capture());
        DecisionTraceCommand trace = traceCaptor.getValue();
        assertThat(trace.decisionId()).isEqualTo(result.decisionId());
        assertThat(trace.protectionRequestId()).isEqualTo(result.protectionRequestId());
        assertThat(trace.requestFingerprint()).hasSize(64);
        assertThat(trace.riskScore()).isEqualTo(30);
        assertThat(trace.outcome()).isEqualTo("REQUIRE_STEP_UP");
        assertThat(trace.normalizedContext()).containsEntry("failedAttempts", 5);
        assertThat(trace.normalizedContext()).containsEntry("signalProvider", "CLIENT_SUPPLIED");
        assertThat(trace.normalizedContext()).containsEntry("signalConfidence", "HIGH");
        assertThat(trace.normalizedContext()).containsEntry("signalSimulated", true);
        assertThat(trace.normalizedContext()).containsEntry("reasonCatalogVersion", "risk-reason-catalog-1.0");
        assertThat(trace.normalizedContext()).containsEntry("decisionEngineVersion", "decision-engine-1.0");
        assertThat(trace.reasons())
                .extracting(reason -> reason.code() + ":" + reason.contribution())
                .containsExactly("FAILED_ATTEMPTS:15", "NEW_DEVICE:15");
    }

    @Test
    void staleSignalEnvelopeIsRejectedBeforeAnySideEffect() {
        RiskSignalEnvelope staleEnvelope = new RiskSignalEnvelope(
                new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW),
                "CLIENT_SUPPLIED",
                Instant.parse("2026-07-20T02:00:00Z"),
                SignalConfidence.HIGH,
                null,
                true);

        org.junit.jupiter.api.Assertions.assertThrows(
                io.github.viniciusssantos.accountshield.protection.StaleRiskSignalException.class,
                () -> service.decide(new ProtectionDecisionCommand(
                        "account-opaque-stale",
                        ProtectionEventType.LOGIN_ATTEMPT,
                        staleEnvelope,
                        null)));

        verify(protectionRequestRepository, org.mockito.Mockito.never()).save(any());
        verify(decisionTraceRecorder, org.mockito.Mockito.never()).record(any());

        var counter = meterRegistry.find("accountshield.protection.degraded_decisions")
                .tag("reason", "RISK_SIGNAL_STALE")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void activePolicyUnavailableIncrementsDegradedDecisionCounter() {
        RiskSignals signals = new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW);
        RiskSignalEnvelope envelope = new RiskSignalEnvelope(
                signals, "CLIENT_SUPPLIED", Instant.parse("2026-07-20T03:00:00Z"), SignalConfidence.HIGH, null, true);
        RiskAssessment assessment = new RiskAssessment(0, RiskBand.LOW, "risk-rules-1.0", List.of());
        when(riskAssessmentService.assess(envelope)).thenReturn(assessment);
        when(policyEvaluationService.evaluate("account-protection-default", 0))
                .thenThrow(new io.github.viniciusssantos.accountshield.policy.ActivePolicyUnavailableException(
                        "account-protection-default"));
        when(idempotencyGuard.claim(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(IdempotencyResult.absent());

        org.junit.jupiter.api.Assertions.assertThrows(
                io.github.viniciusssantos.accountshield.policy.ActivePolicyUnavailableException.class,
                () -> service.decide(new ProtectionDecisionCommand(
                        "account-opaque-policy-unavailable",
                        ProtectionEventType.LOGIN_ATTEMPT,
                        envelope,
                        null)));

        verify(protectionRequestRepository, org.mockito.Mockito.never()).save(any());

        var counter = meterRegistry.find("accountshield.protection.degraded_decisions")
                .tag("reason", "ACTIVE_POLICY_UNAVAILABLE")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void challengeProviderFailureDuringStepUpDegradesToTemporarilyBlock() {
        RiskSignals signals = new RiskSignals(5, true, false, false, NetworkRiskLevel.LOW);
        RiskSignalEnvelope envelope = new RiskSignalEnvelope(
                signals, "CLIENT_SUPPLIED", Instant.parse("2026-07-20T03:00:00Z"), SignalConfidence.HIGH, null, true);
        RiskAssessment assessment = new RiskAssessment(
                30, RiskBand.MEDIUM, "risk-rules-1.0", List.of(new RiskReason("FAILED_ATTEMPTS", 30)));
        when(riskAssessmentService.assess(envelope)).thenReturn(assessment);
        when(policyEvaluationService.evaluate("account-protection-default", 30))
                .thenReturn(new PolicyEvaluation(
                        "account-protection-default", "1.0.0", ProtectionOutcome.REQUIRE_STEP_UP));
        when(idempotencyGuard.claim(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(IdempotencyResult.absent());
        when(challengeService.create(any(CreateChallengeCommand.class)))
                .thenThrow(new IllegalStateException("simulated challenge provider outage"));

        var result = service.decide(new ProtectionDecisionCommand(
                "account-opaque-degraded",
                ProtectionEventType.LOGIN_ATTEMPT,
                envelope,
                null));

        assertThat(result.outcome()).isEqualTo(ProtectionOutcome.TEMPORARILY_BLOCK);
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradationReason()).isEqualTo("CHALLENGE_PROVIDER_UNAVAILABLE");
        assertThat(result.challenge()).isNull();

        verify(protectionRequestRepository).save(any());

        ArgumentCaptor<DecisionTraceCommand> traceCaptor = ArgumentCaptor.forClass(DecisionTraceCommand.class);
        verify(decisionTraceRecorder).record(traceCaptor.capture());
        DecisionTraceCommand trace = traceCaptor.getValue();
        assertThat(trace.outcome()).isEqualTo("TEMPORARILY_BLOCK");
        assertThat(trace.normalizedContext()).containsEntry("degraded", true);
        assertThat(trace.normalizedContext()).containsEntry("degradationReason", "CHALLENGE_PROVIDER_UNAVAILABLE");

        ArgumentCaptor<io.github.viniciusssantos.accountshield.protection.ProtectionDecisionMade> eventCaptor =
                ArgumentCaptor.forClass(io.github.viniciusssantos.accountshield.protection.ProtectionDecisionMade.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().outcome()).isEqualTo("TEMPORARILY_BLOCK");
        assertThat(eventCaptor.getValue().degraded()).isTrue();
        assertThat(eventCaptor.getValue().degradationReason()).isEqualTo("CHALLENGE_PROVIDER_UNAVAILABLE");
    }

    @Test
    void routesANonDefaultClientToItsOwnPolicyKeyAndPropagatesClientId() {
        ClientId acmeClientId = new ClientId("acme-corp");
        RiskSignals signals = new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW);
        RiskSignalEnvelope envelope = new RiskSignalEnvelope(
                signals, "CLIENT_SUPPLIED", Instant.parse("2026-07-20T03:00:00Z"), SignalConfidence.HIGH, null, true);
        RiskAssessment assessment = new RiskAssessment(0, RiskBand.LOW, "risk-rules-1.0", List.of());
        when(riskAssessmentService.assess(envelope)).thenReturn(assessment);
        when(policyEvaluationService.evaluate("acme-login-policy", 0))
                .thenReturn(new PolicyEvaluation("acme-login-policy", "1.0.0", ProtectionOutcome.ALLOW));
        when(idempotencyGuard.claim(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(IdempotencyResult.absent());

        PolicyRoutingService acmeAwareRouting = (clientId, eventType) -> {
            assertThat(clientId).isEqualTo("acme-corp");
            assertThat(eventType).isEqualTo("LOGIN_ATTEMPT");
            return "acme-login-policy";
        };
        ProtectionDecisionApplicationService acmeService = new ProtectionDecisionApplicationService(
                riskAssessmentService,
                policyEvaluationService,
                acmeAwareRouting,
                protectionRequestRepository,
                decisionTraceRecorder,
                idempotencyGuard,
                challengeService,
                clock,
                new ObjectMapper(),
                eventPublisher,
                rateLimiter,
                Duration.ofMinutes(5),
                meterRegistry);

        var result = acmeService.decide(new ProtectionDecisionCommand(
                "account-opaque-acme",
                ProtectionEventType.LOGIN_ATTEMPT,
                envelope,
                null,
                acmeClientId));

        assertThat(result.policyKey()).isEqualTo("acme-login-policy");
        assertThat(result.outcome()).isEqualTo(ProtectionOutcome.ALLOW);

        ArgumentCaptor<DecisionTraceCommand> traceCaptor = ArgumentCaptor.forClass(DecisionTraceCommand.class);
        verify(decisionTraceRecorder).record(traceCaptor.capture());
        assertThat(traceCaptor.getValue().normalizedContext()).containsEntry("clientId", "acme-corp");

        ArgumentCaptor<io.github.viniciusssantos.accountshield.protection.ProtectionDecisionMade> eventCaptor =
                ArgumentCaptor.forClass(io.github.viniciusssantos.accountshield.protection.ProtectionDecisionMade.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().clientId()).isEqualTo("acme-corp");
    }
}
