package io.github.viniciusssantos.accountshield.protection;

import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.risk.RiskBand;
import io.github.viniciusssantos.accountshield.risk.RiskReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProtectionDecisionResult(
        UUID decisionId,
        UUID protectionRequestId,
        ProtectionOutcome outcome,
        int riskScore,
        RiskBand riskBand,
        String algorithmVersion,
        String policyKey,
        String policyVersion,
        List<RiskReason> reasons,
        Instant decidedAt) {

    public ProtectionDecisionResult {
        reasons = List.copyOf(reasons);
    }
}
