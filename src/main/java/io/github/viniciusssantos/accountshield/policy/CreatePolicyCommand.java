package io.github.viniciusssantos.accountshield.policy;

public record CreatePolicyCommand(
        String policyKey,
        String version,
        short allowMaxScore,
        short stepUpMaxScore) {
}
