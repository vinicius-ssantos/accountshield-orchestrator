package io.github.viniciusssantos.accountshield.policy;

public final class PolicyVersionNotFoundException extends RuntimeException {

    private final String policyKey;
    private final String version;

    public PolicyVersionNotFoundException(String policyKey, String version) {
        super("policy version not found: " + policyKey + ":" + version);
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
