package io.github.viniciusssantos.accountshield.policy;

public final class IllegalPolicyTransitionException extends RuntimeException {

    public IllegalPolicyTransitionException(String policyKey, String version, String fromStatus, String toStatus) {
        super("illegal policy transition for " + policyKey + ":" + version
                + " — cannot transition from " + fromStatus + " to " + toStatus);
    }
}
