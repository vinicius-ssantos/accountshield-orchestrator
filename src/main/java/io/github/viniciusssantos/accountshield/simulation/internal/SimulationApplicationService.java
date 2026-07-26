package io.github.viniciusssantos.accountshield.simulation.internal;

import io.github.viniciusssantos.accountshield.audit.DecisionTraceQuery;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceView;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluation;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationContext;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationService;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskAlgorithmRegistry;
import io.github.viniciusssantos.accountshield.risk.RiskAssessment;
import io.github.viniciusssantos.accountshield.risk.RiskAssessmentService;
import io.github.viniciusssantos.accountshield.risk.RiskBand;
import io.github.viniciusssantos.accountshield.risk.RiskReason;
import io.github.viniciusssantos.accountshield.risk.RiskSignalEnvelope;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import io.github.viniciusssantos.accountshield.risk.SignalConfidence;
import io.github.viniciusssantos.accountshield.simulation.ReplayResult;
import io.github.viniciusssantos.accountshield.simulation.ShadowEvaluationResult;
import io.github.viniciusssantos.accountshield.simulation.SimulationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SimulationApplicationService implements SimulationService {

    private static final String DEFAULT_SIGNAL_PROVIDER = "CLIENT_SUPPLIED";

    private final DecisionTraceQuery decisionTraceQuery;
    private final PolicyEvaluationService policyEvaluationService;
    private final RiskAlgorithmRegistry riskAlgorithmRegistry;

    SimulationApplicationService(
            DecisionTraceQuery decisionTraceQuery,
            PolicyEvaluationService policyEvaluationService,
            RiskAlgorithmRegistry riskAlgorithmRegistry) {
        this.decisionTraceQuery = decisionTraceQuery;
        this.policyEvaluationService = policyEvaluationService;
        this.riskAlgorithmRegistry = riskAlgorithmRegistry;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReplayResult> replay(UUID protectionRequestId) {
        Objects.requireNonNull(protectionRequestId, "protectionRequestId must not be null");

        Optional<DecisionTraceView> traceOpt = decisionTraceQuery.findByProtectionRequestId(protectionRequestId);
        if (traceOpt.isEmpty()) {
            return Optional.empty();
        }

        DecisionTraceView trace = traceOpt.get();

        RiskAssessmentService algorithm = riskAlgorithmRegistry.resolve(trace.algorithmVersion());
        RiskSignalEnvelope reconstructed = reconstructEnvelope(trace);
        RiskAssessment recomputed = algorithm.assess(reconstructed);

        boolean recoveryRequest = Boolean.TRUE.equals(trace.normalizedContext().get("recoveryRequest"));
        PolicyEvaluation replayedPolicy = recoveryRequest
                ? policyEvaluationService.evaluateVersion(
                        trace.policyKey(), trace.policyVersion(), recomputed.score(),
                        PolicyEvaluationContext.recoveryRequestContext())
                : policyEvaluationService.evaluateVersion(
                        trace.policyKey(), trace.policyVersion(), recomputed.score());

        RiskBand originalBand = RiskBand.fromScore(trace.riskScore());
        List<RiskReason> originalReasons = trace.reasons().stream()
                .map(reason -> new RiskReason(reason.code(), reason.contribution()))
                .toList();

        List<String> mismatches = new ArrayList<>();
        if (recomputed.score() != trace.riskScore()) {
            mismatches.add("riskScore: expected " + trace.riskScore() + " but replay produced "
                    + recomputed.score());
        }
        if (recomputed.band() != originalBand) {
            mismatches.add("riskBand: expected " + originalBand + " but replay produced " + recomputed.band());
        }
        if (!originalReasons.equals(recomputed.reasons())) {
            mismatches.add("reasons: expected " + originalReasons + " but replay produced " + recomputed.reasons());
        }
        if (!replayedPolicy.outcome().name().equals(trace.outcome())) {
            mismatches.add("outcome: expected " + trace.outcome() + " but replay produced "
                    + replayedPolicy.outcome().name());
        }

        return Optional.of(new ReplayResult(
                protectionRequestId,
                mismatches.isEmpty(),
                trace.outcome(),
                replayedPolicy.outcome().name(),
                trace.riskScore(),
                recomputed.score(),
                originalBand,
                recomputed.band(),
                originalReasons,
                recomputed.reasons(),
                trace.policyKey(),
                trace.policyVersion(),
                trace.algorithmVersion(),
                mismatches));
    }

    @Override
    @Transactional(readOnly = true)
    public ShadowEvaluationResult evaluateShadow(
            String policyKey,
            int riskScore,
            String candidatePolicyVersion) {
        Objects.requireNonNull(policyKey, "policyKey must not be null");
        Objects.requireNonNull(candidatePolicyVersion, "candidatePolicyVersion must not be null");

        PolicyEvaluation live = policyEvaluationService.evaluate(policyKey, riskScore);
        PolicyEvaluation shadow = policyEvaluationService.evaluateVersion(
                policyKey, candidatePolicyVersion, riskScore);

        return ShadowEvaluationResult.of(
                live.outcome(),
                shadow.outcome(),
                live.policyVersion(),
                shadow.policyVersion(),
                riskScore);
    }

    /**
     * Reconstructs the exact signal envelope a historical decision was made with, from its
     * persisted normalized_context. Provenance fields added after #45 (provider/observedAt/
     * confidence/schemaVersion/simulated) are defaulted the same way the original request-parsing
     * layer defaults them, so traces recorded before that change still replay correctly — only
     * confidence actually affects the recomputed score (LOW_CONFIDENCE_SIGNAL).
     */
    private RiskSignalEnvelope reconstructEnvelope(DecisionTraceView trace) {
        Map<String, Object> context = trace.normalizedContext();
        RiskSignals signals = new RiskSignals(
                ((Number) context.get("failedAttempts")).intValue(),
                Boolean.TRUE.equals(context.get("newDevice")),
                Boolean.TRUE.equals(context.get("impossibleTravel")),
                Boolean.TRUE.equals(context.get("compromisedCredential")),
                NetworkRiskLevel.valueOf((String) context.get("networkRiskLevel")));

        String provider = context.containsKey("signalProvider")
                ? (String) context.get("signalProvider") : DEFAULT_SIGNAL_PROVIDER;
        Instant observedAt = context.containsKey("signalObservedAt")
                ? Instant.parse((String) context.get("signalObservedAt")) : trace.decidedAt();
        SignalConfidence confidence = context.containsKey("signalConfidence")
                ? SignalConfidence.valueOf((String) context.get("signalConfidence")) : SignalConfidence.HIGH;
        String schemaVersion = (String) context.get("signalSchemaVersion");
        boolean simulated = context.containsKey("signalSimulated")
                ? Boolean.TRUE.equals(context.get("signalSimulated")) : true;

        return new RiskSignalEnvelope(signals, provider, observedAt, confidence, schemaVersion, simulated);
    }
}
