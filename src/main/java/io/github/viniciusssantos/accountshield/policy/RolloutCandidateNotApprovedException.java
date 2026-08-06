package io.github.viniciusssantos.accountshield.policy;

public final class RolloutCandidateNotApprovedException extends RuntimeException {

    private final String policyKey;
    private final String version;

    public RolloutCandidateNotApprovedException(String policyKey, String version) {
        super("policy " + policyKey + ":" + version + " must be APPROVED before it can enter rollout");
        this.policyKey = policyKey;
        this.version = version;
    }

    public String policyKey() {
        return policyKey;
    }

    public String version() {
        return version;
    }
}
