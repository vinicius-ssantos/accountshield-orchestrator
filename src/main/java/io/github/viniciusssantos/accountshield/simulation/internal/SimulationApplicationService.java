package io.github.viniciusssantos.accountshield.simulation.internal;

import io.github.viniciusssantos.accountshield.audit.DecisionTraceQuery;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceView;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluation;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationContext;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationService;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.simulation.ReplayResult;
import io.github.viniciusssantos.accountshield.simulation.ShadowEvaluationResult;
import io.github.viniciusssantos.accountshield.simulation.SimulationService;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SimulationApplicationService implements SimulationService {

    private final DecisionTraceQuery decisionTraceQuery;
    private final PolicyEvaluationService policyEvaluationService;

    SimulationApplicationService(
            DecisionTraceQuery decisionTraceQuery,
            PolicyEvaluationService policyEvaluationService) {
        this.decisionTraceQuery = decisionTraceQuery;
        this.policyEvaluationService = policyEvaluationService;
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

        PolicyEvaluationContext context = Boolean.TRUE.equals(
                trace.normalizedContext().get("recoveryRequest"))
                ? PolicyEvaluationContext.recoveryRequestContext()
                : PolicyEvaluationContext.standard();
        PolicyEvaluation replayed = policyEvaluationService.evaluateVersion(
                trace.policyKey(),
                trace.policyVersion(),
                trace.riskScore(),
                context);

        if (replayed.outcome().name().equals(trace.outcome())
                && replayed.outcome().name().equals(trace.outcome())) {
            return Optional.of(ReplayResult.matching(
                    protectionRequestId,
                    trace.outcome(),
                    trace.riskScore(),
                    trace.policyKey(),
                    trace.policyVersion()));
        }

        return Optional.of(ReplayResult.mismatch(
                protectionRequestId,
                trace.outcome(),
                replayed.outcome().name(),
                trace.riskScore(),
                trace.riskScore(),
                trace.policyKey(),
                trace.policyVersion()));
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
}
