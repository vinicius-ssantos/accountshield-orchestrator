package io.github.viniciusssantos.accountshield.policy;

public record CreatePolicyCommand(
        String policyKey,
        String version,
        short allowMaxScore,
        short stepUpMaxScore,
        short recoveryMaxScore) {

    public CreatePolicyCommand(
            String policyKey,
            String version,
            short allowMaxScore,
            short stepUpMaxScore) {
        this(policyKey, version, allowMaxScore, stepUpMaxScore, (short) 89);
    }
}
