package io.github.viniciusssantos.accountshield.risk;

public interface RiskAlgorithmRegistry {

    /**
     * Resolves the {@link RiskAssessmentService} implementation that produces the given
     * algorithm version. Throws {@link UnknownAlgorithmVersionException} when no implementation
     * is registered for it.
     */
    RiskAssessmentService resolve(String algorithmVersion);
}
