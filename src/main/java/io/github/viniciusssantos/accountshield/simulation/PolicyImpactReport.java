package io.github.viniciusssantos.accountshield.simulation;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record PolicyImpactReport(
        String policyKey,
        String candidatePolicyVersion,
        Set<String> originalPolicyVersionsObserved,
        Set<String> algorithmVersionsObserved,
        int totalDecisions,
        int divergentDecisionsCount,
        double divergencePercentage,
        double maxDivergencePercentageThreshold,
        boolean exceedsDivergenceThreshold,
        Map<String, Map<String, Long>> transitionMatrix,
        Map<String, PolicySegmentImpact> impactByEventType,
        Map<String, PolicySegmentImpact> impactByRiskBand,
        List<DivergentDecision> divergentDecisions) {

    public PolicyImpactReport {
        originalPolicyVersionsObserved = Set.copyOf(originalPolicyVersionsObserved);
        algorithmVersionsObserved = Set.copyOf(algorithmVersionsObserved);
        transitionMatrix = Map.copyOf(transitionMatrix);
        impactByEventType = Map.copyOf(impactByEventType);
        impactByRiskBand = Map.copyOf(impactByRiskBand);
        divergentDecisions = List.copyOf(divergentDecisions);
    }
}
