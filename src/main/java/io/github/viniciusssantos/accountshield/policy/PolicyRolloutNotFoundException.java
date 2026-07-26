package io.github.viniciusssantos.accountshield.policy;

public final class PolicyRolloutNotFoundException extends RuntimeException {

    private final String policyKey;

    public PolicyRolloutNotFoundException(String policyKey) {
        super("no active rollout exists for policy key: " + policyKey);
        this.policyKey = policyKey;
    }

    public String policyKey() {
        return policyKey;
    }
}
