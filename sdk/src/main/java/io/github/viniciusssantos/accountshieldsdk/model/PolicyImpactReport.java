package io.github.viniciusssantos.accountshieldsdk.model;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Mirrors {@code POST /api/v1/simulation/policy-impact}'s response body exactly. */
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

    public record PolicySegmentImpact(String segment, int totalDecisions, int divergentDecisions) {
    }

    /** {@code redactedAccountReference} is already redacted server-side -- never the raw account reference. */
    public record DivergentDecision(
            UUID protectionRequestId,
            String redactedAccountReference,
            String originalOutcome,
            String candidateOutcome,
            int riskScore,
            List<ProtectionDecisionResponse.Reason> originalReasons) {
    }
}
