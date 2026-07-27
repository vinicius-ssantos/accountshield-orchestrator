package io.github.viniciusssantos.accountshield.risk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskAssessment;
import io.github.viniciusssantos.accountshield.risk.RiskSignalEnvelope;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import io.github.viniciusssantos.accountshield.risk.SignalConfidence;
import java.time.Instant;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Issue #53: "risk score always remains in its valid range." A jqwik property test over the full
 * generated input space of {@code RiskSignals}, rather than the handful of hand-picked example
 * cases {@code DeterministicRiskAssessmentServiceTest} already covers. jqwik reports the exact
 * failing sample and a reproducible seed automatically on any property failure -- this alone
 * satisfies issue #53's "generated tests use reproducible seeds on failure" and "fuzz failures
 * preserve request artifacts" acceptance criteria for every property test in this suite, with no
 * extra code needed (see ADR 0033).
 */
class RiskScorePropertyTest {

    private final DeterministicRiskAssessmentService service = new DeterministicRiskAssessmentService();

    @Property
    void scoreAndReasonsAreAlwaysWellFormed(
            @ForAll @IntRange(min = 0, max = 20) int failedAttempts,
            @ForAll boolean newDevice,
            @ForAll boolean impossibleTravel,
            @ForAll boolean compromisedCredential,
            @ForAll("networkRiskLevels") NetworkRiskLevel networkRiskLevel,
            @ForAll("confidences") SignalConfidence confidence) {
        RiskSignalEnvelope envelope = new RiskSignalEnvelope(
                new RiskSignals(failedAttempts, newDevice, impossibleTravel, compromisedCredential, networkRiskLevel),
                "CLIENT_SUPPLIED", Instant.now(), confidence, null, true);

        RiskAssessment assessment = service.assess(envelope);

        assertThat(assessment.score()).isBetween(0, 100);
        assertThat(assessment.band()).isNotNull();
        assertThat(assessment.reasons())
                .allSatisfy(reason -> assertThat(reason.contribution()).isBetween(-100, 100));
    }

    @Provide
    Arbitrary<NetworkRiskLevel> networkRiskLevels() {
        return Arbitraries.of(NetworkRiskLevel.values());
    }

    @Provide
    Arbitrary<SignalConfidence> confidences() {
        return Arbitraries.of(SignalConfidence.values());
    }
}
