package io.github.viniciusssantos.accountshield.risk;

public interface RiskAssessmentService {

    RiskAssessment assess(RiskSignalEnvelope envelope);
}
