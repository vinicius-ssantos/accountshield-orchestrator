package io.github.viniciusssantos.accountshield.policy;

public final class IllegalPolicyTransitionException extends RuntimeException {

    private final String policyKey;
    private final String version;
    private final String fromStatus;
    private final String toStatus;

    public IllegalPolicyTransitionException(String policyKey, String version, String fromStatus, String toStatus) {
        super("illegal policy transition for " + policyKey + ":" + version
                + " — cannot transition from " + fromStatus + " to " + toStatus);
        this.policyKey = policyKey;
        this.version = version;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    public String policyKey() {
        return policyKey;
    }

    public String version() {
        return version;
    }

    public String fromStatus() {
        return fromStatus;
    }

    public String toStatus() {
        return toStatus;
    }
}
