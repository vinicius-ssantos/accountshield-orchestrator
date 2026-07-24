package io.github.viniciusssantos.accountshield.policy;

public record PolicyEvaluationContext(boolean recoveryRequest) {

    private static final PolicyEvaluationContext STANDARD = new PolicyEvaluationContext(false);
    private static final PolicyEvaluationContext RECOVERY = new PolicyEvaluationContext(true);

    public static PolicyEvaluationContext standard() {
        return STANDARD;
    }

    public static PolicyEvaluationContext recoveryRequestContext() {
        return RECOVERY;
    }
}
