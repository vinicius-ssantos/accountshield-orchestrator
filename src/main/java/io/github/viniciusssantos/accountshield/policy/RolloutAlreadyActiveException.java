package io.github.viniciusssantos.accountshield.policy;

public final class RolloutAlreadyActiveException extends RuntimeException {

    private final String policyKey;

    public RolloutAlreadyActiveException(String policyKey) {
        super("an active rollout already exists for policy key: " + policyKey);
        this.policyKey = policyKey;
    }

    public String policyKey() {
        return policyKey;
    }
}
