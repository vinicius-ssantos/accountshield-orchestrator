package io.github.viniciusssantos.accountshield.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PolicyAnalyzerTest {

    private final PolicyAnalyzer analyzer = new PolicyAnalyzer();

    @Test
    void cleanPolicyProducesZeroDiagnostics() {
        PolicyAnalysisResult result = analyzer.analyze(
                new PolicyDefinition((short) 29, (short) 69, (short) 89));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.analyzerVersion()).isEqualTo(PolicyAnalysisResult.CURRENT_ANALYZER_VERSION);
    }

    @Test
    void missingAllowMaxScoreIsReported() {
        PolicyAnalysisResult result = analyzer.analyze(
                new PolicyDefinition(null, (short) 69, (short) 89));

        assertThat(result.diagnostics()).extracting("code").contains("ALLOW_MAX_SCORE_MISSING");
        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void missingStepUpMaxScoreIsReported() {
        PolicyAnalysisResult result = analyzer.analyze(
                new PolicyDefinition((short) 29, null, (short) 89));

        assertThat(result.diagnostics()).extracting("code").contains("STEP_UP_MAX_SCORE_MISSING");
    }

    @Test
    void missingRecoveryMaxScoreIsReported() {
        PolicyAnalysisResult result = analyzer.analyze(
                new PolicyDefinition((short) 29, (short) 69, null));

        assertThat(result.diagnostics()).extracting("code").contains("RECOVERY_MAX_SCORE_MISSING");
    }

    @Test
    void outOfRangeThresholdsAreReported() {
        assertThat(analyzer.analyze(new PolicyDefinition((short) -1, (short) 69, (short) 89))
                .diagnostics()).extracting("code").contains("ALLOW_MAX_SCORE_OUT_OF_RANGE");
        assertThat(analyzer.analyze(new PolicyDefinition((short) 29, (short) 100, (short) 89))
                .diagnostics()).extracting("code").contains("STEP_UP_MAX_SCORE_OUT_OF_RANGE");
        assertThat(analyzer.analyze(new PolicyDefinition((short) 29, (short) 69, (short) 100))
                .diagnostics()).extracting("code").contains("RECOVERY_MAX_SCORE_OUT_OF_RANGE");
    }

    @Test
    void shadowedStepUpBandIsDetectedWhenAllowMeetsOrExceedsStepUp() {
        PolicyAnalysisResult equal = analyzer.analyze(
                new PolicyDefinition((short) 70, (short) 70, (short) 89));
        PolicyAnalysisResult inverted = analyzer.analyze(
                new PolicyDefinition((short) 80, (short) 70, (short) 89));

        assertThat(equal.diagnostics()).extracting("code").contains("STEP_UP_BAND_SHADOWED");
        assertThat(inverted.diagnostics()).extracting("code").contains("STEP_UP_BAND_SHADOWED");
        assertThat(equal.hasErrors()).isTrue();
    }

    @Test
    void shadowingIsDeterministicAcrossTheFullBoundedInputSpace() {
        for (short allow = 0; allow <= 99; allow++) {
            for (short stepUp = 1; stepUp <= 99; stepUp++) {
                PolicyAnalysisResult result = analyzer.analyze(
                        new PolicyDefinition(allow, stepUp, (short) 89));
                boolean shadowed = result.diagnostics().stream()
                        .anyMatch(d -> d.code().equals("STEP_UP_BAND_SHADOWED"));
                assertThat(shadowed).as("allow=%d stepUp=%d", allow, stepUp).isEqualTo(allow >= stepUp);
            }
        }
    }

    @Test
    void recoveryThresholdMoreRestrictiveThanAllowIsAWarningNotAnError() {
        PolicyAnalysisResult result = analyzer.analyze(
                new PolicyDefinition((short) 40, (short) 70, (short) 10));

        assertThat(result.diagnostics()).extracting("code")
                .contains("RECOVERY_THRESHOLD_MORE_RESTRICTIVE_THAN_ALLOW");
        assertThat(result.diagnostics().stream()
                .filter(d -> d.code().equals("RECOVERY_THRESHOLD_MORE_RESTRICTIVE_THAN_ALLOW"))
                .findFirst().orElseThrow().severity())
                .isEqualTo(PolicySeverity.WARNING);
        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void analysisIsDeterministicForEqualInput() {
        PolicyDefinition definition = new PolicyDefinition((short) 29, (short) 69, (short) 89);

        assertThat(analyzer.analyze(definition)).isEqualTo(analyzer.analyze(definition));
    }

    @Test
    void missingThresholdIsNotDoubleReportedAsOutOfRange() {
        PolicyAnalysisResult result = analyzer.analyze(new PolicyDefinition(null, null, null));

        assertThat(result.diagnostics()).extracting("code")
                .containsExactlyInAnyOrder(
                        "ALLOW_MAX_SCORE_MISSING", "STEP_UP_MAX_SCORE_MISSING", "RECOVERY_MAX_SCORE_MISSING");
    }
}
