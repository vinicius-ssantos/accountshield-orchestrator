package io.github.viniciusssantos.accountshield.policy;

public interface PolicyEvaluationService {

    PolicyEvaluation evaluate(String policyKey, int riskScore);
}
