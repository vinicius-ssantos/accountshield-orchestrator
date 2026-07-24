package io.github.viniciusssantos.accountshield.policy;

public final class PendingPolicyVersionExistsException extends RuntimeException {

    private final String policyKey;

    public PendingPolicyVersionExistsException(String policyKey) {
        super("a draft or validated policy version already exists for key: " + policyKey);
        this.policyKey = policyKey;
    }

    public String policyKey() {
        return policyKey;
    }
}
