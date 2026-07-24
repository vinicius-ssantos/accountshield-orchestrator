package io.github.viniciusssantos.accountshield.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RiskSignalEnvelopeTest {

    private final RiskSignals signals = new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW);

    @Test
    void rejectsNullSignals() {
        assertThatThrownBy(() -> new RiskSignalEnvelope(
                null, "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsBlankProvider() {
        assertThatThrownBy(() -> new RiskSignalEnvelope(
                signals, "  ", Instant.now(), SignalConfidence.HIGH, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider");
    }

    @Test
    void rejectsNullObservedAt() {
        assertThatThrownBy(() -> new RiskSignalEnvelope(
                signals, "CLIENT_SUPPLIED", null, SignalConfidence.HIGH, null, true))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullConfidence() {
        assertThatThrownBy(() -> new RiskSignalEnvelope(
                signals, "CLIENT_SUPPLIED", Instant.now(), null, null, true))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void defaultsSchemaVersionWhenNotProvided() {
        RiskSignalEnvelope envelope = new RiskSignalEnvelope(
                signals, "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true);

        assertThat(envelope.schemaVersion()).isEqualTo(RiskSignalEnvelope.CURRENT_SCHEMA_VERSION);
    }

    @Test
    void isStaleWhenObservedAtIsOlderThanMaxAge() {
        Instant now = Instant.parse("2026-07-24T12:00:00Z");
        RiskSignalEnvelope envelope = new RiskSignalEnvelope(
                signals, "CLIENT_SUPPLIED", now.minus(Duration.ofMinutes(10)), SignalConfidence.HIGH, null, true);

        assertThat(envelope.isStale(now, Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void isNotStaleWhenObservedAtIsWithinMaxAge() {
        Instant now = Instant.parse("2026-07-24T12:00:00Z");
        RiskSignalEnvelope envelope = new RiskSignalEnvelope(
                signals, "CLIENT_SUPPLIED", now.minus(Duration.ofMinutes(1)), SignalConfidence.HIGH, null, true);

        assertThat(envelope.isStale(now, Duration.ofMinutes(5))).isFalse();
    }

    @Test
    void isNotStaleAtExactlyMaxAge() {
        Instant now = Instant.parse("2026-07-24T12:00:00Z");
        RiskSignalEnvelope envelope = new RiskSignalEnvelope(
                signals, "CLIENT_SUPPLIED", now.minus(Duration.ofMinutes(5)), SignalConfidence.HIGH, null, true);

        assertThat(envelope.isStale(now, Duration.ofMinutes(5))).isFalse();
    }
}
