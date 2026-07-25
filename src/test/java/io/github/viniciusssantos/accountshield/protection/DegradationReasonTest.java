package io.github.viniciusssantos.accountshield.protection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DegradationReasonTest {

    @Test
    void activePolicyUnavailableFailsClosedWithoutProducingADecision() {
        assertThat(DegradationReason.ACTIVE_POLICY_UNAVAILABLE.strategy())
                .isEqualTo(DegradationStrategy.FAIL_CLOSED);
        assertThat(DegradationReason.ACTIVE_POLICY_UNAVAILABLE.producesDecision()).isFalse();
    }

    @Test
    void riskSignalStaleRejectsWithoutProducingADecision() {
        assertThat(DegradationReason.RISK_SIGNAL_STALE.strategy())
                .isEqualTo(DegradationStrategy.REJECT_UNAVAILABLE);
        assertThat(DegradationReason.RISK_SIGNAL_STALE.producesDecision()).isFalse();
    }

    @Test
    void challengeProviderUnavailableFailsClosedButStillProducesADecision() {
        assertThat(DegradationReason.CHALLENGE_PROVIDER_UNAVAILABLE.strategy())
                .isEqualTo(DegradationStrategy.FAIL_CLOSED);
        assertThat(DegradationReason.CHALLENGE_PROVIDER_UNAVAILABLE.producesDecision()).isTrue();
    }
}
