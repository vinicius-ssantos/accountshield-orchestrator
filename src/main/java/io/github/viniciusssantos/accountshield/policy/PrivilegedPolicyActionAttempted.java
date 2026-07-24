package io.github.viniciusssantos.accountshield.policy;

public record PrivilegedPolicyActionAttempted(
        String policyKey,
        String version,
        String action,
        String actor,
        boolean authorized) {
}
