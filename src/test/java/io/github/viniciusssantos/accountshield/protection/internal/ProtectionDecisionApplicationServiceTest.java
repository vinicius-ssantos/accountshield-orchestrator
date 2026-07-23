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
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
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
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import java.time.Clock;
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
    private final ProtectionRequestRepository protectionRequestRepository = mock(ProtectionRequestRepository.class);
    private final DecisionTraceRecorder decisionTraceRecorder = mock(DecisionTraceRecorder.class);
    private final IdempotencyGuard idempotencyGuard = mock(IdempotencyGuard.class);
    private final ChallengeService challengeService = mock(ChallengeService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-20T03:00:00Z"), ZoneOffset.UTC);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ProtectionRateLimiter rateLimiter = (accountReference, now) -> {};

    private final ProtectionDecisionApplicationService service = new ProtectionDecisionApplicationService(
            riskAssessmentService,
            policyEvaluationService,
            protectionRequestRepository,
            decisionTraceRecorder,
            idempotencyGuard,
            challengeService,
            clock,
            new ObjectMapper(),
            eventPublisher,
            rateLimiter);

    @Test
    void persistsAndReturnsTheSameExplainableDecision() {
        RiskSignals signals = new RiskSignals(5, true, false, false, NetworkRiskLevel.LOW);
        RiskAssessment assessment = new RiskAssessment(
                30,
                RiskBand.MEDIUM,
                "risk-rules-1.0",
                List.of(new RiskReason("FAILED_ATTEMPTS", 15), new RiskReason("NEW_DEVICE", 15)));
        when(riskAssessmentService.assess(signals)).thenReturn(assessment);
        when(policyEvaluationService.evaluate("account-protection-default", 30))
                .thenReturn(new PolicyEvaluation(
                        "account-protection-default",
                        "1.0.0",
                        ProtectionOutcome.REQUIRE_STEP_UP));
        when(idempotencyGuard.resolve(anyString(), anyString(), any()))
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
                signals,
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

        ArgumentCaptor<DecisionTraceCommand> traceCaptor = ArgumentCaptor.forClass(DecisionTraceCommand.class);
        verify(decisionTraceRecorder).record(traceCaptor.capture());
        DecisionTraceCommand trace = traceCaptor.getValue();
        assertThat(trace.decisionId()).isEqualTo(result.decisionId());
        assertThat(trace.protectionRequestId()).isEqualTo(result.protectionRequestId());
        assertThat(trace.requestFingerprint()).hasSize(64);
        assertThat(trace.riskScore()).isEqualTo(30);
        assertThat(trace.outcome()).isEqualTo("REQUIRE_STEP_UP");
        assertThat(trace.normalizedContext()).containsEntry("failedAttempts", 5);
        assertThat(trace.reasons())
                .extracting(reason -> reason.code() + ":" + reason.contribution())
                .containsExactly("FAILED_ATTEMPTS:15", "NEW_DEVICE:15");
    }
}
