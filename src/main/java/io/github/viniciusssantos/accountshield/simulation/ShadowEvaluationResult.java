package io.github.viniciusssantos.accountshield.simulation;

import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;

public record ShadowEvaluationResult(
        String liveOutcome,
        String shadowOutcome,
        String livePolicyVersion,
        String shadowPolicyVersion,
        int riskScore,
        boolean diverged) {

    public static ShadowEvaluationResult of(
            ProtectionOutcome liveOutcome,
            ProtectionOutcome shadowOutcome,
            String livePolicyVersion,
            String shadowPolicyVersion,
            int riskScore) {
        return new ShadowEvaluationResult(
                liveOutcome.name(),
                shadowOutcome.name(),
                livePolicyVersion,
                shadowPolicyVersion,
                riskScore,
                liveOutcome != shadowOutcome);
    }
}
