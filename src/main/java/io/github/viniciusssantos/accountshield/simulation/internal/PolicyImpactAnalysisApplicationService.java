package io.github.viniciusssantos.accountshield.simulation.internal;

import io.github.viniciusssantos.accountshield.audit.DecisionTraceQuery;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceView;
import io.github.viniciusssantos.accountshield.outbox.AccountPseudonymizer;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluation;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationContext;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationService;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.risk.RiskBand;
import io.github.viniciusssantos.accountshield.risk.RiskReason;
import io.github.viniciusssantos.accountshield.simulation.DivergentDecision;
import io.github.viniciusssantos.accountshield.simulation.PolicyImpactAnalysisService;
import io.github.viniciusssantos.accountshield.simulation.PolicyImpactReport;
import io.github.viniciusssantos.accountshield.simulation.PolicySegmentImpact;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PolicyImpactAnalysisApplicationService implements PolicyImpactAnalysisService {

    private static final int MAX_DIVERGENT_DECISIONS_LISTED = 200;
    private static final String UNKNOWN_EVENT_TYPE = "UNKNOWN";

    private final DecisionTraceQuery decisionTraceQuery;
    private final PolicyEvaluationService policyEvaluationService;
    private final AccountPseudonymizer pseudonymizer;
    private final double maxDivergencePercentage;

    PolicyImpactAnalysisApplicationService(
            DecisionTraceQuery decisionTraceQuery,
            PolicyEvaluationService policyEvaluationService,
            AccountPseudonymizer pseudonymizer,
            @Value("${accountshield.policy.impact.max-divergence-percentage:20}") double maxDivergencePercentage) {
        this.decisionTraceQuery = decisionTraceQuery;
        this.policyEvaluationService = policyEvaluationService;
        this.pseudonymizer = pseudonymizer;
        this.maxDivergencePercentage = maxDivergencePercentage;
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyImpactReport analyzeImpact(String policyKey, String candidatePolicyVersion, int maxSamples) {
        Objects.requireNonNull(policyKey, "policyKey must not be null");
        Objects.requireNonNull(candidatePolicyVersion, "candidatePolicyVersion must not be null");
        if (maxSamples <= 0) {
            throw new IllegalArgumentException("maxSamples must be positive");
        }

        // validated up front so a candidate version that doesn't exist fails fast even when
        // policyKey has no historical decisions yet
        policyEvaluationService.evaluateVersion(
                policyKey, candidatePolicyVersion, 0, PolicyEvaluationContext.standard());

        List<DecisionTraceView> traces = decisionTraceQuery.findRecentByPolicyKey(policyKey, maxSamples);

        Map<String, Map<String, Long>> transitionMatrix = emptyTransitionMatrix();
        Map<String, int[]> eventTypeTotals = new LinkedHashMap<>();
        Map<String, int[]> riskBandTotals = new LinkedHashMap<>();
        Set<String> policyVersionsObserved = new LinkedHashSet<>();
        Set<String> algorithmVersionsObserved = new LinkedHashSet<>();
        List<DivergentDecision> divergentDecisions = new ArrayList<>();
        int divergentCount = 0;

        for (DecisionTraceView trace : traces) {
            policyVersionsObserved.add(trace.policyVersion());
            algorithmVersionsObserved.add(trace.algorithmVersion());

            PolicyEvaluationContext context = Boolean.TRUE.equals(trace.normalizedContext().get("recoveryRequest"))
                    ? PolicyEvaluationContext.recoveryRequestContext()
                    : PolicyEvaluationContext.standard();
            PolicyEvaluation candidateEvaluation = policyEvaluationService.evaluateVersion(
                    policyKey, candidatePolicyVersion, trace.riskScore(), context);

            String originalOutcome = trace.outcome();
            String candidateOutcome = candidateEvaluation.outcome().name();
            transitionMatrix.get(originalOutcome).merge(candidateOutcome, 1L, Long::sum);

            String eventType = (String) trace.normalizedContext()
                    .getOrDefault("protectionEventType", UNKNOWN_EVENT_TYPE);
            String riskBand = RiskBand.fromScore(trace.riskScore()).name();
            int[] eventTotals = eventTypeTotals.computeIfAbsent(eventType, key -> new int[2]);
            int[] bandTotals = riskBandTotals.computeIfAbsent(riskBand, key -> new int[2]);
            eventTotals[0]++;
            bandTotals[0]++;

            if (!originalOutcome.equals(candidateOutcome)) {
                divergentCount++;
                eventTotals[1]++;
                bandTotals[1]++;
                if (divergentDecisions.size() < MAX_DIVERGENT_DECISIONS_LISTED) {
                    divergentDecisions.add(new DivergentDecision(
                            trace.protectionRequestId(),
                            pseudonymizer.pseudonymize(trace.accountReference()),
                            originalOutcome,
                            candidateOutcome,
                            trace.riskScore(),
                            trace.reasons().stream()
                                    .map(reason -> new RiskReason(reason.code(), reason.contribution()))
                                    .toList()));
                }
            }
        }

        int total = traces.size();
        double divergencePercentage = total == 0 ? 0.0 : (divergentCount * 100.0) / total;

        return new PolicyImpactReport(
                policyKey,
                candidatePolicyVersion,
                policyVersionsObserved,
                algorithmVersionsObserved,
                total,
                divergentCount,
                divergencePercentage,
                maxDivergencePercentage,
                divergencePercentage > maxDivergencePercentage,
                transitionMatrix,
                toSegmentImpact(eventTypeTotals),
                toSegmentImpact(riskBandTotals),
                divergentDecisions);
    }

    private Map<String, Map<String, Long>> emptyTransitionMatrix() {
        Map<String, Map<String, Long>> matrix = new LinkedHashMap<>();
        for (ProtectionOutcome from : ProtectionOutcome.values()) {
            Map<String, Long> row = new LinkedHashMap<>();
            for (ProtectionOutcome to : ProtectionOutcome.values()) {
                row.put(to.name(), 0L);
            }
            matrix.put(from.name(), row);
        }
        return matrix;
    }

    private Map<String, PolicySegmentImpact> toSegmentImpact(Map<String, int[]> totals) {
        Map<String, PolicySegmentImpact> result = new LinkedHashMap<>();
        totals.forEach((segment, counts) ->
                result.put(segment, new PolicySegmentImpact(segment, counts[0], counts[1])));
        return result;
    }
}
