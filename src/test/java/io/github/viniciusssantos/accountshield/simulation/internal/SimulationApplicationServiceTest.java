package io.github.viniciusssantos.accountshield.simulation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.audit.DecisionReasonContribution;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceQuery;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceView;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluation;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationService;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.risk.RiskAlgorithmRegistry;
import io.github.viniciusssantos.accountshield.risk.RiskAssessment;
import io.github.viniciusssantos.accountshield.risk.RiskAssessmentService;
import io.github.viniciusssantos.accountshield.risk.RiskBand;
import io.github.viniciusssantos.accountshield.risk.RiskReason;
import io.github.viniciusssantos.accountshield.risk.UnknownAlgorithmVersionException;
import io.github.viniciusssantos.accountshield.simulation.ReplayResult;
import io.github.viniciusssantos.accountshield.simulation.ShadowEvaluationResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SimulationApplicationServiceTest {

    private final DecisionTraceQuery decisionTraceQuery = mock(DecisionTraceQuery.class);
    private final PolicyEvaluationService policyEvaluationService = mock(PolicyEvaluationService.class);
    private final RiskAlgorithmRegistry riskAlgorithmRegistry = mock(RiskAlgorithmRegistry.class);
    private SimulationApplicationService service;

    @BeforeEach
    void setUp() {
        service = new SimulationApplicationService(
                decisionTraceQuery, policyEvaluationService, riskAlgorithmRegistry);
    }

    @Test
    void replayFullyMatchesWhenReconstructedInputReproducesHistory() {
        UUID requestId = UUID.randomUUID();
        List<DecisionReasonContribution> originalReasons =
                List.of(new DecisionReasonContribution("FAILED_ATTEMPTS", 15, Map.of()));
        when(decisionTraceQuery.findByProtectionRequestId(requestId)).thenReturn(Optional.of(
                trace(requestId, "ALLOW", 15, "1.0.0", "risk-rules-1.0", fullSignalContext(), originalReasons)));
        RiskAssessmentService algorithm = stubAlgorithm(
                new RiskAssessment(15, RiskBand.LOW, "risk-rules-1.0",
                        List.of(new RiskReason("FAILED_ATTEMPTS", 15))));
        when(riskAlgorithmRegistry.resolve("risk-rules-1.0")).thenReturn(algorithm);
        when(policyEvaluationService.evaluateVersion("account-protection-default", "1.0.0", 15))
                .thenReturn(new PolicyEvaluation("account-protection-default", "1.0.0", ProtectionOutcome.ALLOW));

        Optional<ReplayResult> result = service.replay(requestId);

        assertThat(result).isPresent();
        assertThat(result.get().matches()).isTrue();
        assertThat(result.get().mismatches()).isEmpty();
        assertThat(result.get().originalOutcome()).isEqualTo("ALLOW");
        assertThat(result.get().replayedOutcome()).isEqualTo("ALLOW");
        assertThat(result.get().originalRiskBand()).isEqualTo(RiskBand.LOW);
        assertThat(result.get().replayedRiskBand()).isEqualTo(RiskBand.LOW);
    }

    @Test
    void replayDetectsReasonMismatch() {
        UUID requestId = UUID.randomUUID();
        List<DecisionReasonContribution> originalReasons =
                List.of(new DecisionReasonContribution("FAILED_ATTEMPTS", 15, Map.of()));
        when(decisionTraceQuery.findByProtectionRequestId(requestId)).thenReturn(Optional.of(
                trace(requestId, "ALLOW", 15, "1.0.0", "risk-rules-1.0", fullSignalContext(), originalReasons)));
        RiskAssessmentService algorithm = stubAlgorithm(
                new RiskAssessment(15, RiskBand.LOW, "risk-rules-1.0",
                        List.of(new RiskReason("NEW_DEVICE", 15))));
        when(riskAlgorithmRegistry.resolve("risk-rules-1.0")).thenReturn(algorithm);
        when(policyEvaluationService.evaluateVersion("account-protection-default", "1.0.0", 15))
                .thenReturn(new PolicyEvaluation("account-protection-default", "1.0.0", ProtectionOutcome.ALLOW));

        Optional<ReplayResult> result = service.replay(requestId);

        assertThat(result).isPresent();
        assertThat(result.get().matches()).isFalse();
        assertThat(result.get().mismatches()).anyMatch(m -> m.startsWith("reasons:"));
    }

    @Test
    void replayDetectsOutcomeMismatchDrivenByRecomputedScoreWithoutMutatingHistory() {
        UUID requestId = UUID.randomUUID();
        when(decisionTraceQuery.findByProtectionRequestId(requestId)).thenReturn(Optional.of(
                trace(requestId, "ALLOW", 15, "1.0.0", "risk-rules-1.0", fullSignalContext(), List.of())));
        RiskAssessmentService algorithm = stubAlgorithm(
                new RiskAssessment(80, RiskBand.HIGH, "risk-rules-1.0",
                        List.of(new RiskReason("COMPROMISED_CREDENTIAL", 40), new RiskReason("IMPOSSIBLE_TRAVEL", 35),
                                new RiskReason("FAILED_ATTEMPTS", 5))));
        when(riskAlgorithmRegistry.resolve("risk-rules-1.0")).thenReturn(algorithm);
        when(policyEvaluationService.evaluateVersion("account-protection-default", "1.0.0", 80))
                .thenReturn(new PolicyEvaluation(
                        "account-protection-default", "1.0.0", ProtectionOutcome.TEMPORARILY_BLOCK));

        Optional<ReplayResult> result = service.replay(requestId);

        assertThat(result).isPresent();
        assertThat(result.get().matches()).isFalse();
        assertThat(result.get().mismatches()).anyMatch(m -> m.startsWith("riskScore:"));
        assertThat(result.get().mismatches()).anyMatch(m -> m.startsWith("riskBand:"));
        assertThat(result.get().mismatches()).anyMatch(m -> m.startsWith("outcome:"));
        assertThat(result.get().replayedOutcome()).isEqualTo("TEMPORARILY_BLOCK");
        // history itself is never touched -- evaluateVersion is a read-only, version-pinned call
        assertThat(result.get().policyVersion()).isEqualTo("1.0.0");
    }

    @Test
    void replayThrowsForUnknownAlgorithmVersion() {
        UUID requestId = UUID.randomUUID();
        when(decisionTraceQuery.findByProtectionRequestId(requestId)).thenReturn(Optional.of(
                trace(requestId, "ALLOW", 15, "1.0.0", "risk-rules-9.9", fullSignalContext(), List.of())));
        when(riskAlgorithmRegistry.resolve("risk-rules-9.9"))
                .thenThrow(new UnknownAlgorithmVersionException("risk-rules-9.9"));

        assertThatThrownBy(() -> service.replay(requestId))
                .isInstanceOf(UnknownAlgorithmVersionException.class);
    }

    @Test
    void replayReconstructsLegacyTraceMissingProvenanceFields() {
        UUID requestId = UUID.randomUUID();
        Map<String, Object> legacyContext = Map.of(
                "failedAttempts", 0, "newDevice", false,
                "impossibleTravel", false, "compromisedCredential", false,
                "networkRiskLevel", "LOW");
        when(decisionTraceQuery.findByProtectionRequestId(requestId)).thenReturn(Optional.of(
                trace(requestId, "ALLOW", 0, "1.0.0", "risk-rules-1.0", legacyContext, List.of())));
        RiskAssessmentService algorithm = stubAlgorithm(
                new RiskAssessment(0, RiskBand.LOW, "risk-rules-1.0", List.of()));
        when(riskAlgorithmRegistry.resolve("risk-rules-1.0")).thenReturn(algorithm);
        when(policyEvaluationService.evaluateVersion("account-protection-default", "1.0.0", 0))
                .thenReturn(new PolicyEvaluation("account-protection-default", "1.0.0", ProtectionOutcome.ALLOW));

        Optional<ReplayResult> result = service.replay(requestId);

        assertThat(result).isPresent();
        assertThat(result.get().matches()).isTrue();
    }

    @Test
    void replayReturnsEmptyWhenTraceNotFound() {
        UUID requestId = UUID.randomUUID();
        when(decisionTraceQuery.findByProtectionRequestId(requestId))
                .thenReturn(Optional.empty());

        Optional<ReplayResult> result = service.replay(requestId);

        assertThat(result).isEmpty();
    }

    @Test
    void shadowEvaluationReturnsDivergedWhenOutcomesDiffer() {
        when(policyEvaluationService.evaluate("account-protection-default", 50))
                .thenReturn(new PolicyEvaluation("account-protection-default", "1.0.0", ProtectionOutcome.REQUIRE_STEP_UP));
        when(policyEvaluationService.evaluateVersion("account-protection-default", "2.0.0", 50))
                .thenReturn(new PolicyEvaluation("account-protection-default", "2.0.0", ProtectionOutcome.TEMPORARILY_BLOCK));

        ShadowEvaluationResult result = service.evaluateShadow(
                "account-protection-default", 50, "2.0.0");

        assertThat(result.diverged()).isTrue();
        assertThat(result.liveOutcome()).isEqualTo("REQUIRE_STEP_UP");
        assertThat(result.shadowOutcome()).isEqualTo("TEMPORARILY_BLOCK");
        assertThat(result.shadowPolicyVersion()).isEqualTo("2.0.0");
    }

    @Test
    void shadowEvaluationReturnsConvergedWhenOutcomesMatch() {
        when(policyEvaluationService.evaluate(anyString(), anyInt()))
                .thenReturn(new PolicyEvaluation("account-protection-default", "1.0.0", ProtectionOutcome.ALLOW));
        when(policyEvaluationService.evaluateVersion(anyString(), eq("2.0.0"), anyInt()))
                .thenReturn(new PolicyEvaluation("account-protection-default", "2.0.0", ProtectionOutcome.ALLOW));

        ShadowEvaluationResult result = service.evaluateShadow(
                "account-protection-default", 10, "2.0.0");

        assertThat(result.diverged()).isFalse();
    }

    private Map<String, Object> fullSignalContext() {
        return Map.of(
                "failedAttempts", 5, "newDevice", false,
                "impossibleTravel", false, "compromisedCredential", false,
                "networkRiskLevel", "LOW",
                "signalProvider", "CLIENT_SUPPLIED",
                "signalObservedAt", "2026-07-21T12:00:00Z",
                "signalConfidence", "HIGH",
                "signalSimulated", true);
    }

    private RiskAssessmentService stubAlgorithm(RiskAssessment result) {
        RiskAssessmentService stub = mock(RiskAssessmentService.class);
        when(stub.assess(org.mockito.ArgumentMatchers.any())).thenReturn(result);
        return stub;
    }

    private DecisionTraceView trace(
            UUID requestId, String outcome, int riskScore, String policyVersion, String algorithmVersion,
            Map<String, Object> normalizedContext, List<DecisionReasonContribution> reasons) {
        return new DecisionTraceView(
                UUID.randomUUID(), requestId, "user-ref", "fingerprint",
                algorithmVersion, "account-protection-default", policyVersion,
                outcome, riskScore,
                normalizedContext,
                Instant.parse("2026-07-21T12:00:00Z"),
                reasons);
    }
}
