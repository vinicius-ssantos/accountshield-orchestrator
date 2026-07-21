package io.github.viniciusssantos.accountshield.risk;

public interface RiskAssessmentService {

    RiskAssessment assess(RiskSignals signals);
}
