package io.github.viniciusssantos.accountshield.risk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskAssessment;
import io.github.viniciusssantos.accountshield.risk.RiskBand;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import org.junit.jupiter.api.Test;

class DeterministicRiskAssessmentServiceTest {

    private final DeterministicRiskAssessmentService service = new DeterministicRiskAssessmentService();

    @Test
    void returnsLowRiskWhenNoRiskSignalsArePresent() {
        RiskAssessment assessment = service.assess(new RiskSignals(
                0, false, false, false, NetworkRiskLevel.LOW));

        assertThat(assessment.score()).isZero();
        assertThat(assessment.band()).isEqualTo(RiskBand.LOW);
        assertThat(assessment.algorithmVersion()).isEqualTo("risk-rules-1.0");
        assertThat(assessment.reasons()).isEmpty();
    }

    @Test
    void explainsEveryPointInStablePriorityOrder() {
        RiskAssessment assessment = service.assess(new RiskSignals(
                5, true, true, true, NetworkRiskLevel.HIGH));

        assertThat(assessment.score()).isEqualTo(100);
        assertThat(assessment.band()).isEqualTo(RiskBand.HIGH);
        assertThat(assessment.reasons())
                .extracting(reason -> reason.code() + ":" + reason.contribution())
                .containsExactly(
                        "COMPROMISED_CREDENTIAL:40",
                        "IMPOSSIBLE_TRAVEL:35",
                        "FAILED_ATTEMPTS:15",
                        "NETWORK_RISK_HIGH:10");
        assertThat(assessment.reasons().stream().mapToInt(reason -> reason.contribution()).sum())
                .isEqualTo(100);
    }

    @Test
    void producesTheSameAssessmentForTheSameNormalizedSignals() {
        RiskSignals signals = new RiskSignals(4, true, false, false, NetworkRiskLevel.MEDIUM);

        assertThat(service.assess(signals)).isEqualTo(service.assess(signals));
    }

    @Test
    void rejectsUnboundedFailedAttemptCounts() {
        assertThatThrownBy(() -> new RiskSignals(21, false, false, false, NetworkRiskLevel.LOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failedAttempts");
    }
}
