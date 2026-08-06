package io.github.viniciusssantos.accountshield.risk;

public interface RiskAssessmentService {

    RiskAssessment assess(RiskSignalEnvelope envelope);

    /**
     * The deterministic algorithm version this implementation produces. Used by
     * {@link RiskAlgorithmRegistry} to self-register — a new algorithm version is added by
     * registering a new implementation, not by editing the registry.
     */
    String algorithmVersion();
}
