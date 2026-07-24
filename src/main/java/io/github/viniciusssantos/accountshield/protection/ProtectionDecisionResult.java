package io.github.viniciusssantos.accountshield.protection;

import io.github.viniciusssantos.accountshield.challenge.ChallengePlan;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.risk.RiskBand;
import io.github.viniciusssantos.accountshield.risk.RiskReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProtectionDecisionResult(
        UUID decisionId,
        UUID protectionRequestId,
        UUID recoveryAuthorizationId,
        ProtectionOutcome outcome,
        int riskScore,
        RiskBand riskBand,
        String algorithmVersion,
        String policyKey,
        String policyVersion,
        List<RiskReason> reasons,
        Instant decidedAt,
        ChallengePlan challenge) {

    public ProtectionDecisionResult {
        reasons = List.copyOf(reasons);
    }

    public ProtectionDecisionResult(
            UUID decisionId,
            UUID protectionRequestId,
            ProtectionOutcome outcome,
            int riskScore,
            RiskBand riskBand,
            String algorithmVersion,
            String policyKey,
            String policyVersion,
            List<RiskReason> reasons,
            Instant decidedAt,
            ChallengePlan challenge) {
        this(decisionId, protectionRequestId, null, outcome, riskScore, riskBand,
                algorithmVersion, policyKey, policyVersion, reasons, decidedAt, challenge);
    }

    public ProtectionDecisionResult withoutChallenge() {
        return new ProtectionDecisionResult(
                decisionId, protectionRequestId, recoveryAuthorizationId,
                outcome, riskScore, riskBand, algorithmVersion, policyKey,
                policyVersion, reasons, decidedAt, null);
    }
}
