package io.github.viniciusssantos.accountshield.policy;

public interface PolicyEvaluationService {

    default PolicyEvaluation evaluate(String policyKey, int riskScore) {
        return evaluate(policyKey, riskScore, PolicyEvaluationContext.standard());
    }

    PolicyEvaluation evaluate(
            String policyKey,
            int riskScore,
            PolicyEvaluationContext context);

    default PolicyEvaluation evaluateVersion(
            String policyKey,
            String policyVersion,
            int riskScore) {
        return evaluateVersion(
                policyKey,
                policyVersion,
                riskScore,
                PolicyEvaluationContext.standard());
    }

    PolicyEvaluation evaluateVersion(
            String policyKey,
            String policyVersion,
            int riskScore,
            PolicyEvaluationContext context);
}
