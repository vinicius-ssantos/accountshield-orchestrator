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
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationContext;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationService;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.protection.RequestFingerprint;
import io.github.viniciusssantos.accountshield.risk.RiskAlgorithmRegistry;
import io.github.viniciusssantos.accountshield.risk.RiskAssessment;
import io.github.viniciusssantos.accountshield.risk.RiskAssessmentService;
import io.github.viniciusssantos.accountshield.risk.RiskBand;
import io.github.viniciusssantos.accountshield.risk.RiskReason;
import io.github.viniciusssantos.accountshield.risk.RiskReasonCatalog;
import io.github.viniciusssantos.accountshield.risk.UnknownAlgorithmVersionException;
import io.github.viniciusssantos.accountshield.simulation.ReplayResult;
import io.github.viniciusssantos.accountshield.simulation.ShadowEvaluationResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SimulationApplicationServiceTest {

    private static final String ACCOUNT_REFERENCE = "user-ref";

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
        Map<String, Object> context = fullSignalContext();
        List<DecisionReasonContribution> originalReasons =
                List.of(new DecisionReasonContribution("FAILED_ATTEMPTS", 15, Map.of()));
        when(decisionTraceQuery.findByProtectionRequestId(requestId)).thenReturn(Optional.of(
                trace(requestId, "ALLOW", 15, "1.0.0", "risk-rules-1.0", context,
                        originalReasons, correctFingerprint(context))));
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
        assertThat(result.get().reasonCatalogVersion()).isEqualTo(RiskReasonCatalog.CURRENT_VERSION);
        assertThat(result.get().decisionEngineVersion()).isEqualTo("decision-engine-1.0");
        assertThat(result.get().normalizedInputSchemaVersion()).isEqualTo("risk-signal-envelope-1.0");
    }

    @Test
    void replayFullyMatchesHistoricalRecoveryRequestFixtureAcrossReleases() {
        UUID requestId = UUID.randomUUID();
        Map<String, Object> context = fullSignalContext();
        context.put("recoveryRequest", true);
        List<DecisionReasonContribution> originalReasons = List.of();
        when(decisionTraceQuery.findByProtectionRequestId(requestId)).thenReturn(Optional.of(
                trace(requestId, "START_RECOVERY", 15, "1.0.0", "risk-rules-1.0", context,
                        originalReasons, correctFingerprint(context))));
        RiskAssessmentService algorithm = stubAlgorithm(
                new RiskAssessment(15, RiskBand.LOW, "risk-rules-1.0", List.of()));
        when(riskAlgorithmRegistry.resolve("risk-rules-1.0")).thenReturn(algorithm);
        when(policyEvaluationService.evaluateVersion(
                        "account-protection-default", "1.0.0", 15, PolicyEvaluationContext.recoveryRequestContext()))
                .thenReturn(new PolicyEvaluation(
                        "account-protection-default", "1.0.0", ProtectionOutcome.START_RECOVERY));

        Optional<ReplayResult> result = service.replay(requestId);

        assertThat(result).isPresent();
        assertThat(result.get().matches()).isTrue();
        assertThat(result.get().mismatches()).isEmpty();
    }

    @Test
    void replayDetectsReasonMismatch() {
        UUID requestId = UUID.randomUUID();
        Map<String, Object> context = fullSignalContext();
        List<DecisionReasonContribution> originalReasons =
                List.of(new DecisionReasonContribution("FAILED_ATTEMPTS", 15, Map.of()));
        when(decisionTraceQuery.findByProtectionRequestId(requestId)).thenReturn(Optional.of(
                trace(requestId, "ALLOW", 15, "1.0.0", "risk-rules-1.0", context,
                        originalReasons, correctFingerprint(context))));
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
        Map<String, Object> context = fullSignalContext();
        when(decisionTraceQuery.findByProtectionRequestId(requestId)).thenReturn(Optional.of(
                trace(requestId, "ALLOW", 15, "1.0.0", "risk-rules-1.0", context,
                        List.of(), correctFingerprint(context))));
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
    void replayDetectsCanonicalInputHashMismatch() {
        UUID requestId = UUID.randomUUID();
        Map<String, Object> context = fullSignalContext();
        when(decisionTraceQuery.findByProtectionRequestId(requestId)).thenReturn(Optional.of(
                trace(requestId, "ALLOW", 15, "1.0.0", "risk-rules-1.0", context,
                        List.of(), "not-the-real-hash")));
        RiskAssessmentService algorithm = stubAlgorithm(
                new RiskAssessment(15, RiskBand.LOW, "risk-rules-1.0", List.of()));
        when(riskAlgorithmRegistry.resolve("risk-rules-1.0")).thenReturn(algorithm);
        when(policyEvaluationService.evaluateVersion("account-protection-default", "1.0.0", 15))
                .thenReturn(new PolicyEvaluation("account-protection-default", "1.0.0", ProtectionOutcome.ALLOW));

        Optional<ReplayResult> result = service.replay(requestId);

        assertThat(result).isPresent();
        assertThat(result.get().matches()).isFalse();
        assertThat(result.get().mismatches()).anyMatch(m -> m.startsWith("canonicalInputHash:"));
    }

    @Test
    void replayDetectsUnknownReasonCatalogCode() {
        UUID requestId = UUID.randomUUID();
        Map<String, Object> context = fullSignalContext();
        List<DecisionReasonContribution> originalReasons =
                List.of(new DecisionReasonContribution("RETIRED_LEGACY_CODE", 15, Map.of()));
        when(decisionTraceQuery.findByProtectionRequestId(requestId)).thenReturn(Optional.of(
                trace(requestId, "ALLOW", 15, "1.0.0", "risk-rules-1.0", context,
                        originalReasons, correctFingerprint(context))));
        RiskAssessmentService algorithm = stubAlgorithm(
                new RiskAssessment(15, RiskBand.LOW, "risk-rules-1.0",
                        List.of(new RiskReason("RETIRED_LEGACY_CODE", 15))));
        when(riskAlgorithmRegistry.resolve("risk-rules-1.0")).thenReturn(algorithm);
        when(policyEvaluationService.evaluateVersion("account-protection-default", "1.0.0", 15))
                .thenReturn(new PolicyEvaluation("account-protection-default", "1.0.0", ProtectionOutcome.ALLOW));

        Optional<ReplayResult> result = service.replay(requestId);

        assertThat(result).isPresent();
        assertThat(result.get().matches()).isFalse();
        assertThat(result.get().mismatches()).anyMatch(m -> m.startsWith("reasonCatalogVersion:"));
    }

    @Test
    void replayThrowsForUnknownAlgorithmVersion() {
        UUID requestId = UUID.randomUUID();
        Map<String, Object> context = fullSignalContext();
        when(decisionTraceQuery.findByProtectionRequestId(requestId)).thenReturn(Optional.of(
                trace(requestId, "ALLOW", 15, "1.0.0", "risk-rules-9.9", context,
                        List.of(), correctFingerprint(context))));
        when(riskAlgorithmRegistry.resolve("risk-rules-9.9"))
                .thenThrow(new UnknownAlgorithmVersionException("risk-rules-9.9"));

        assertThatThrownBy(() -> service.replay(requestId))
                .isInstanceOf(UnknownAlgorithmVersionException.class);
    }

    @Test
    void replayReconstructsLegacyTraceMissingProvenanceFields() {
        UUID requestId = UUID.randomUUID();
        Map<String, Object> legacyContext = new LinkedHashMap<>();
        legacyContext.put("failedAttempts", 0);
        legacyContext.put("newDevice", false);
        legacyContext.put("impossibleTravel", false);
        legacyContext.put("compromisedCredential", false);
        legacyContext.put("networkRiskLevel", "LOW");
        legacyContext.put("protectionEventType", "LOGIN_ATTEMPT");
        when(decisionTraceQuery.findByProtectionRequestId(requestId)).thenReturn(Optional.of(
                trace(requestId, "ALLOW", 0, "1.0.0", "risk-rules-1.0", legacyContext,
                        List.of(), correctFingerprint(legacyContext))));
        RiskAssessmentService algorithm = stubAlgorithm(
                new RiskAssessment(0, RiskBand.LOW, "risk-rules-1.0", List.of()));
        when(riskAlgorithmRegistry.resolve("risk-rules-1.0")).thenReturn(algorithm);
        when(policyEvaluationService.evaluateVersion("account-protection-default", "1.0.0", 0))
                .thenReturn(new PolicyEvaluation("account-protection-default", "1.0.0", ProtectionOutcome.ALLOW));

        Optional<ReplayResult> result = service.replay(requestId);

        assertThat(result).isPresent();
        assertThat(result.get().matches()).isTrue();
        // pre-#43 traces have no reasonCatalogVersion/decisionEngineVersion key at all; the
        // current values are reported since there has only ever been one of each
        assertThat(result.get().reasonCatalogVersion()).isEqualTo(RiskReasonCatalog.CURRENT_VERSION);
        assertThat(result.get().decisionEngineVersion()).isEqualTo("decision-engine-1.0");
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
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("failedAttempts", 5);
        context.put("newDevice", false);
        context.put("impossibleTravel", false);
        context.put("compromisedCredential", false);
        context.put("networkRiskLevel", "LOW");
        context.put("protectionEventType", "LOGIN_ATTEMPT");
        context.put("signalProvider", "CLIENT_SUPPLIED");
        context.put("signalObservedAt", "2026-07-21T12:00:00Z");
        context.put("signalConfidence", "HIGH");
        context.put("signalSimulated", true);
        return context;
    }

    private String correctFingerprint(Map<String, Object> context) {
        return RequestFingerprint.compute(
                "default-client",
                ACCOUNT_REFERENCE,
                (String) context.get("protectionEventType"),
                ((Number) context.get("failedAttempts")).intValue(),
                Boolean.TRUE.equals(context.get("newDevice")),
                Boolean.TRUE.equals(context.get("impossibleTravel")),
                Boolean.TRUE.equals(context.get("compromisedCredential")),
                (String) context.get("networkRiskLevel"));
    }

    private RiskAssessmentService stubAlgorithm(RiskAssessment result) {
        RiskAssessmentService stub = mock(RiskAssessmentService.class);
        when(stub.assess(org.mockito.ArgumentMatchers.any())).thenReturn(result);
        return stub;
    }

    private DecisionTraceView trace(
            UUID requestId, String outcome, int riskScore, String policyVersion, String algorithmVersion,
            Map<String, Object> normalizedContext, List<DecisionReasonContribution> reasons,
            String requestFingerprint) {
        return new DecisionTraceView(
                UUID.randomUUID(), requestId, ACCOUNT_REFERENCE, requestFingerprint,
                algorithmVersion, "account-protection-default", policyVersion,
                outcome, riskScore,
                normalizedContext,
                Instant.parse("2026-07-21T12:00:00Z"),
                reasons);
    }
}
