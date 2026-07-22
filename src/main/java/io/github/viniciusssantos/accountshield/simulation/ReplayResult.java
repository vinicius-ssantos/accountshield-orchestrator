package io.github.viniciusssantos.accountshield.simulation;

import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import java.util.UUID;

public record ReplayResult(
        UUID protectionRequestId,
        String originalOutcome,
        String replayedOutcome,
        int originalRiskScore,
        int replayedRiskScore,
        String policyKey,
        String policyVersion,
        boolean matches) {

    public static ReplayResult matching(
            UUID protectionRequestId,
            String outcome,
            int riskScore,
            String policyKey,
            String policyVersion) {
        return new ReplayResult(
                protectionRequestId, outcome, outcome, riskScore, riskScore,
                policyKey, policyVersion, true);
    }

    public static ReplayResult mismatch(
            UUID protectionRequestId,
            String originalOutcome,
            String replayedOutcome,
            int originalRiskScore,
            int replayedRiskScore,
            String policyKey,
            String policyVersion) {
        return new ReplayResult(
                protectionRequestId, originalOutcome, replayedOutcome,
                originalRiskScore, replayedRiskScore,
                policyKey, policyVersion, false);
    }
}
