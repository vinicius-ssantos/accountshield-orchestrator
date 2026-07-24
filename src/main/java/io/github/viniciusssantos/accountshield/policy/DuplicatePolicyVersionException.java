package io.github.viniciusssantos.accountshield.policy;

public final class DuplicatePolicyVersionException extends RuntimeException {

    private final String policyKey;
    private final String version;

    public DuplicatePolicyVersionException(String policyKey, String version) {
        super("policy version already exists: " + policyKey + ":" + version);
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
