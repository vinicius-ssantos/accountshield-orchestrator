package io.github.viniciusssantos.accountshield.policy;

public final class SelfApprovalNotAllowedException extends RuntimeException {

    private final String policyKey;
    private final String version;
    private final String actor;

    public SelfApprovalNotAllowedException(String policyKey, String version, String actor) {
        super("actor " + actor + " authored policy " + policyKey + ":" + version
                + " and cannot approve it");
        this.policyKey = policyKey;
        this.version = version;
        this.actor = actor;
    }

    public String policyKey() {
        return policyKey;
    }

    public String version() {
        return version;
    }

    public String actor() {
        return actor;
    }
}
