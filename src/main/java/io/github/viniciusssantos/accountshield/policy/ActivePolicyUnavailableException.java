package io.github.viniciusssantos.accountshield.policy;

public final class ActivePolicyUnavailableException extends RuntimeException {

    public ActivePolicyUnavailableException(String policyKey) {
        super("active policy is unavailable for key: " + policyKey);
    }
}
