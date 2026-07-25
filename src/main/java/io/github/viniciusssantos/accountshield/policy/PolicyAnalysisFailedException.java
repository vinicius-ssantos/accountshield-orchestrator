package io.github.viniciusssantos.accountshield.policy;

public final class PolicyAnalysisFailedException extends RuntimeException {

    private final String policyKey;
    private final String version;
    private final PolicyAnalysisResult result;

    public PolicyAnalysisFailedException(String policyKey, String version, PolicyAnalysisResult result) {
        super("policy analysis failed for " + policyKey + ":" + version
                + " — " + result.diagnostics().size() + " diagnostic(s), analyzer " + result.analyzerVersion());
        this.policyKey = policyKey;
        this.version = version;
        this.result = result;
    }

    public String policyKey() {
        return policyKey;
    }

    public String version() {
        return version;
    }

    public PolicyAnalysisResult result() {
        return result;
    }
}
