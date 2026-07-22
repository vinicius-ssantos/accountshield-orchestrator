package io.github.viniciusssantos.accountshield.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DecisionTraceView(
        UUID decisionId,
        UUID protectionRequestId,
        String accountReference,
        String requestFingerprint,
        String algorithmVersion,
        String policyKey,
        String policyVersion,
        String outcome,
        int riskScore,
        Map<String, Object> normalizedContext,
        Instant decidedAt,
        List<DecisionReasonContribution> reasons) {

    public DecisionTraceView {
        normalizedContext = Map.copyOf(normalizedContext);
        reasons = List.copyOf(reasons);
    }
}
