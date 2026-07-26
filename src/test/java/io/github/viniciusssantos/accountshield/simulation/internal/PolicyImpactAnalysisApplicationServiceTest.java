package io.github.viniciusssantos.accountshield.simulation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.audit.DecisionReasonContribution;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceQuery;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceView;
import io.github.viniciusssantos.accountshield.outbox.AccountPseudonymizer;
import io.github.viniciusssantos.accountshield.policy.ActivePolicyUnavailableException;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluation;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationContext;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationService;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.simulation.PolicyImpactReport;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolicyImpactAnalysisApplicationServiceTest {

    private static final String POLICY_KEY = "account-protection-default";
    private static final String CANDIDATE_VERSION = "2.0.0";

    private final DecisionTraceQuery decisionTraceQuery = mock(DecisionTraceQuery.class);
    private final PolicyEvaluationService policyEvaluationService = mock(PolicyEvaluationService.class);
    private final AccountPseudonymizer pseudonymizer = mock(AccountPseudonymizer.class);
    private PolicyImpactAnalysisApplicationService service;

    @BeforeEach
    void setUp() {
        service = new PolicyImpactAnalysisApplicationService(
                decisionTraceQuery, policyEvaluationService, pseudonymizer, 20.0);
        when(policyEvaluationService.evaluateVersion(
                        eq(POLICY_KEY), eq(CANDIDATE_VERSION), eq(0), eq(PolicyEvaluationContext.standard())))
                .thenReturn(new PolicyEvaluation(POLICY_KEY, CANDIDATE_VERSION, ProtectionOutcome.ALLOW));
        when(pseudonymizer.pseudonymize(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("redacted-token");
    }

    @Test
    void reportsFullTransitionMatrixIncludingRecovery() {
        when(decisionTraceQuery.findRecentByPolicyKey(POLICY_KEY, 100)).thenReturn(List.of(
                trace("ALLOW", 10, false),
                trace("REQUIRE_STEP_UP", 40, false),
                trace("TEMPORARILY_BLOCK", 90, false),
                trace("START_RECOVERY", 20, true)));
        when(policyEvaluationService.evaluateVersion(POLICY_KEY, CANDIDATE_VERSION, 10, PolicyEvaluationContext.standard()))
                .thenReturn(new PolicyEvaluation(POLICY_KEY, CANDIDATE_VERSION, ProtectionOutcome.REQUIRE_STEP_UP));
        when(policyEvaluationService.evaluateVersion(POLICY_KEY, CANDIDATE_VERSION, 40, PolicyEvaluationContext.standard()))
                .thenReturn(new PolicyEvaluation(POLICY_KEY, CANDIDATE_VERSION, ProtectionOutcome.REQUIRE_STEP_UP));
        when(policyEvaluationService.evaluateVersion(POLICY_KEY, CANDIDATE_VERSION, 90, PolicyEvaluationContext.standard()))
                .thenReturn(new PolicyEvaluation(POLICY_KEY, CANDIDATE_VERSION, ProtectionOutcome.TEMPORARILY_BLOCK));
        when(policyEvaluationService.evaluateVersion(
                        POLICY_KEY, CANDIDATE_VERSION, 20, PolicyEvaluationContext.recoveryRequestContext()))
                .thenReturn(new PolicyEvaluation(POLICY_KEY, CANDIDATE_VERSION, ProtectionOutcome.START_RECOVERY));

        PolicyImpactReport report = service.analyzeImpact(POLICY_KEY, CANDIDATE_VERSION, 100);

        assertThat(report.totalDecisions()).isEqualTo(4);
        assertThat(report.divergentDecisionsCount()).isEqualTo(1);
        assertThat(report.transitionMatrix().get("ALLOW").get("REQUIRE_STEP_UP")).isEqualTo(1L);
        assertThat(report.transitionMatrix().get("REQUIRE_STEP_UP").get("REQUIRE_STEP_UP")).isEqualTo(1L);
        assertThat(report.transitionMatrix().get("TEMPORARILY_BLOCK").get("TEMPORARILY_BLOCK")).isEqualTo(1L);
        assertThat(report.transitionMatrix().get("START_RECOVERY").get("START_RECOVERY")).isEqualTo(1L);
    }

    @Test
    void segmentsImpactByEventTypeAndRiskBand() {
        when(decisionTraceQuery.findRecentByPolicyKey(POLICY_KEY, 100)).thenReturn(List.of(
                trace("ALLOW", 10, "LOGIN_ATTEMPT"),
                trace("ALLOW", 15, "LOGIN_ATTEMPT"),
                trace("ALLOW", 80, "PASSWORD_RESET_ATTEMPT")));
        when(policyEvaluationService.evaluateVersion(POLICY_KEY, CANDIDATE_VERSION, 10, PolicyEvaluationContext.standard()))
                .thenReturn(new PolicyEvaluation(POLICY_KEY, CANDIDATE_VERSION, ProtectionOutcome.ALLOW));
        when(policyEvaluationService.evaluateVersion(POLICY_KEY, CANDIDATE_VERSION, 15, PolicyEvaluationContext.standard()))
                .thenReturn(new PolicyEvaluation(POLICY_KEY, CANDIDATE_VERSION, ProtectionOutcome.REQUIRE_STEP_UP));
        when(policyEvaluationService.evaluateVersion(POLICY_KEY, CANDIDATE_VERSION, 80, PolicyEvaluationContext.standard()))
                .thenReturn(new PolicyEvaluation(POLICY_KEY, CANDIDATE_VERSION, ProtectionOutcome.TEMPORARILY_BLOCK));

        PolicyImpactReport report = service.analyzeImpact(POLICY_KEY, CANDIDATE_VERSION, 100);

        assertThat(report.impactByEventType().get("LOGIN_ATTEMPT").totalDecisions()).isEqualTo(2);
        assertThat(report.impactByEventType().get("LOGIN_ATTEMPT").divergentDecisions()).isEqualTo(1);
        assertThat(report.impactByEventType().get("PASSWORD_RESET_ATTEMPT").totalDecisions()).isEqualTo(1);
        assertThat(report.impactByRiskBand().get("LOW").totalDecisions()).isEqualTo(2);
        assertThat(report.impactByRiskBand().get("HIGH").totalDecisions()).isEqualTo(1);
        assertThat(report.impactByRiskBand().get("HIGH").divergentDecisions()).isEqualTo(1);
    }

    @Test
    void divergentDecisionsRedactTheAccountReference() {
        DecisionTraceView divergentTrace = new DecisionTraceView(
                UUID.randomUUID(), UUID.randomUUID(), "sensitive-account-42", "fp",
                "risk-rules-1.0", POLICY_KEY, "1.0.0", "ALLOW", 10,
                fullContext("LOGIN_ATTEMPT", false),
                Instant.parse("2026-07-20T00:00:00Z"),
                List.of(new DecisionReasonContribution("FAILED_ATTEMPTS", 10, Map.of())));
        when(decisionTraceQuery.findRecentByPolicyKey(POLICY_KEY, 100)).thenReturn(List.of(divergentTrace));
        when(policyEvaluationService.evaluateVersion(POLICY_KEY, CANDIDATE_VERSION, 10, PolicyEvaluationContext.standard()))
                .thenReturn(new PolicyEvaluation(POLICY_KEY, CANDIDATE_VERSION, ProtectionOutcome.REQUIRE_STEP_UP));

        PolicyImpactReport report = service.analyzeImpact(POLICY_KEY, CANDIDATE_VERSION, 100);

        assertThat(report.divergentDecisions()).hasSize(1);
        assertThat(report.divergentDecisions().get(0).redactedAccountReference()).isEqualTo("redacted-token");
        assertThat(report.toString()).doesNotContain("sensitive-account-42");
    }

    @Test
    void doesNotExceedThresholdWhenDivergenceEqualsConfiguredMaximum() {
        when(decisionTraceQuery.findRecentByPolicyKey(POLICY_KEY, 100)).thenReturn(List.of(
                trace("ALLOW", 10, false),
                trace("ALLOW", 11, false),
                trace("ALLOW", 12, false),
                trace("ALLOW", 13, false),
                trace("ALLOW", 14, false)));
        when(policyEvaluationService.evaluateVersion(POLICY_KEY, CANDIDATE_VERSION, 10, PolicyEvaluationContext.standard()))
                .thenReturn(new PolicyEvaluation(POLICY_KEY, CANDIDATE_VERSION, ProtectionOutcome.REQUIRE_STEP_UP));
        when(policyEvaluationService.evaluateVersion(POLICY_KEY, CANDIDATE_VERSION, 11, PolicyEvaluationContext.standard()))
                .thenReturn(new PolicyEvaluation(POLICY_KEY, CANDIDATE_VERSION, ProtectionOutcome.ALLOW));
        when(policyEvaluationService.evaluateVersion(POLICY_KEY, CANDIDATE_VERSION, 12, PolicyEvaluationContext.standard()))
                .thenReturn(new PolicyEvaluation(POLICY_KEY, CANDIDATE_VERSION, ProtectionOutcome.ALLOW));
        when(policyEvaluationService.evaluateVersion(POLICY_KEY, CANDIDATE_VERSION, 13, PolicyEvaluationContext.standard()))
                .thenReturn(new PolicyEvaluation(POLICY_KEY, CANDIDATE_VERSION, ProtectionOutcome.ALLOW));
        when(policyEvaluationService.evaluateVersion(POLICY_KEY, CANDIDATE_VERSION, 14, PolicyEvaluationContext.standard()))
                .thenReturn(new PolicyEvaluation(POLICY_KEY, CANDIDATE_VERSION, ProtectionOutcome.ALLOW));

        PolicyImpactReport report = service.analyzeImpact(POLICY_KEY, CANDIDATE_VERSION, 100);

        assertThat(report.divergencePercentage()).isEqualTo(20.0);
        assertThat(report.exceedsDivergenceThreshold()).isFalse();
    }

    @Test
    void exceedsThresholdWhenDivergenceIsStrictlyAboveConfiguredMaximum() {
        when(decisionTraceQuery.findRecentByPolicyKey(POLICY_KEY, 100)).thenReturn(List.of(
                trace("ALLOW", 10, false),
                trace("ALLOW", 11, false),
                trace("ALLOW", 12, false),
                trace("ALLOW", 13, false)));
        when(policyEvaluationService.evaluateVersion(POLICY_KEY, CANDIDATE_VERSION, 10, PolicyEvaluationContext.standard()))
                .thenReturn(new PolicyEvaluation(POLICY_KEY, CANDIDATE_VERSION, ProtectionOutcome.REQUIRE_STEP_UP));
        when(policyEvaluationService.evaluateVersion(POLICY_KEY, CANDIDATE_VERSION, 11, PolicyEvaluationContext.standard()))
                .thenReturn(new PolicyEvaluation(POLICY_KEY, CANDIDATE_VERSION, ProtectionOutcome.ALLOW));
        when(policyEvaluationService.evaluateVersion(POLICY_KEY, CANDIDATE_VERSION, 12, PolicyEvaluationContext.standard()))
                .thenReturn(new PolicyEvaluation(POLICY_KEY, CANDIDATE_VERSION, ProtectionOutcome.ALLOW));
        when(policyEvaluationService.evaluateVersion(POLICY_KEY, CANDIDATE_VERSION, 13, PolicyEvaluationContext.standard()))
                .thenReturn(new PolicyEvaluation(POLICY_KEY, CANDIDATE_VERSION, ProtectionOutcome.ALLOW));

        PolicyImpactReport report = service.analyzeImpact(POLICY_KEY, CANDIDATE_VERSION, 100);

        assertThat(report.divergencePercentage()).isEqualTo(25.0);
        assertThat(report.exceedsDivergenceThreshold()).isTrue();
    }

    @Test
    void legacyTraceMissingRecoveryRequestKeyDefaultsToStandardContext() {
        DecisionTraceView legacyTrace = new DecisionTraceView(
                UUID.randomUUID(), UUID.randomUUID(), "acct-legacy", "fp",
                "risk-rules-1.0", POLICY_KEY, "1.0.0", "ALLOW", 10,
                Map.of("failedAttempts", 1), Instant.parse("2026-07-20T00:00:00Z"), List.of());
        when(decisionTraceQuery.findRecentByPolicyKey(POLICY_KEY, 100)).thenReturn(List.of(legacyTrace));
        when(policyEvaluationService.evaluateVersion(POLICY_KEY, CANDIDATE_VERSION, 10, PolicyEvaluationContext.standard()))
                .thenReturn(new PolicyEvaluation(POLICY_KEY, CANDIDATE_VERSION, ProtectionOutcome.ALLOW));

        PolicyImpactReport report = service.analyzeImpact(POLICY_KEY, CANDIDATE_VERSION, 100);

        assertThat(report.divergentDecisionsCount()).isEqualTo(0);
        assertThat(report.algorithmVersionsObserved()).containsExactly("risk-rules-1.0");
        assertThat(report.originalPolicyVersionsObserved()).containsExactly("1.0.0");
    }

    @Test
    void throwsWhenCandidatePolicyVersionDoesNotExistEvenWithNoHistoricalTraces() {
        when(policyEvaluationService.evaluateVersion(
                        POLICY_KEY, "9.9.9", 0, PolicyEvaluationContext.standard()))
                .thenThrow(new ActivePolicyUnavailableException(POLICY_KEY));

        assertThatThrownBy(() -> service.analyzeImpact(POLICY_KEY, "9.9.9", 100))
                .isInstanceOf(ActivePolicyUnavailableException.class);
    }

    private DecisionTraceView trace(String outcome, int riskScore, boolean recoveryRequest) {
        return trace(outcome, riskScore, "LOGIN_ATTEMPT", recoveryRequest);
    }

    private DecisionTraceView trace(String outcome, int riskScore, String eventType) {
        return trace(outcome, riskScore, eventType, false);
    }

    private DecisionTraceView trace(String outcome, int riskScore, String eventType, boolean recoveryRequest) {
        return new DecisionTraceView(
                UUID.randomUUID(), UUID.randomUUID(), "acct-" + UUID.randomUUID(), "fp",
                "risk-rules-1.0", POLICY_KEY, "1.0.0", outcome, riskScore,
                fullContext(eventType, recoveryRequest),
                Instant.parse("2026-07-20T00:00:00Z"),
                List.of(new DecisionReasonContribution("FAILED_ATTEMPTS", Math.max(riskScore, 1), Map.of())));
    }

    private Map<String, Object> fullContext(String eventType, boolean recoveryRequest) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("protectionEventType", eventType);
        context.put("recoveryRequest", recoveryRequest);
        return context;
    }
}
