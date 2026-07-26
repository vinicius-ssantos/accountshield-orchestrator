package io.github.viniciusssantos.accountshield.simulation;

public interface PolicyImpactAnalysisService {

    /**
     * Evaluates a candidate policy version against the most recent historical decisions made
     * under {@code policyKey} (bounded by {@code maxSamples}), comparing each decision's recorded
     * outcome against what the candidate version would have produced for the same risk score.
     * Side-effect-free: no challenge, recovery, outbox, or audit-write dependency is reachable.
     */
    PolicyImpactReport analyzeImpact(String policyKey, String candidatePolicyVersion, int maxSamples);
}
