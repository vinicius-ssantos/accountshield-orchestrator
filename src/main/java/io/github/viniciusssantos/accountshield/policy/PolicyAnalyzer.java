package io.github.viniciusssantos.accountshield.policy;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Deterministic, side-effect-free semantic analysis of a policy's numeric-threshold
 * definition. Detects null/out-of-range thresholds and score bands that are shadowed or
 * unreachable under {@code policy.internal.DatabasePolicyEvaluationService}'s evaluation rules.
 */
@Component
public class PolicyAnalyzer {

    public PolicyAnalysisResult analyze(PolicyDefinition definition) {
        List<PolicyDiagnostic> diagnostics = new ArrayList<>();

        Short allowMaxScore = definition.allowMaxScore();
        Short stepUpMaxScore = definition.stepUpMaxScore();
        Short recoveryMaxScore = definition.recoveryMaxScore();

        boolean allowValid = checkPresentAndInRange(
                diagnostics, allowMaxScore, "allowMaxScore",
                "ALLOW_MAX_SCORE_MISSING", "ALLOW_MAX_SCORE_OUT_OF_RANGE", (short) 0, (short) 99);
        boolean stepUpValid = checkPresentAndInRange(
                diagnostics, stepUpMaxScore, "stepUpMaxScore",
                "STEP_UP_MAX_SCORE_MISSING", "STEP_UP_MAX_SCORE_OUT_OF_RANGE", (short) 1, (short) 99);
        boolean recoveryValid = checkPresentAndInRange(
                diagnostics, recoveryMaxScore, "recoveryMaxScore",
                "RECOVERY_MAX_SCORE_MISSING", "RECOVERY_MAX_SCORE_OUT_OF_RANGE", (short) 0, (short) 99);

        if (allowValid && stepUpValid && allowMaxScore >= stepUpMaxScore) {
            diagnostics.add(new PolicyDiagnostic(
                    "STEP_UP_BAND_SHADOWED",
                    PolicySeverity.ERROR,
                    "stepUpMaxScore",
                    "allowMaxScore (" + allowMaxScore + ") must be less than stepUpMaxScore ("
                            + stepUpMaxScore + "); otherwise the REQUIRE_STEP_UP band is unreachable "
                            + "and shadowed entirely by ALLOW."));
        }

        if (allowValid && recoveryValid && recoveryMaxScore < allowMaxScore) {
            diagnostics.add(new PolicyDiagnostic(
                    "RECOVERY_THRESHOLD_MORE_RESTRICTIVE_THAN_ALLOW",
                    PolicySeverity.WARNING,
                    "recoveryMaxScore",
                    "recoveryMaxScore (" + recoveryMaxScore + ") is lower than allowMaxScore ("
                            + allowMaxScore + "); recovery requests are usually expected to be at least "
                            + "as permissive as standard ALLOW."));
        }

        return new PolicyAnalysisResult(PolicyAnalysisResult.CURRENT_ANALYZER_VERSION, diagnostics);
    }

    private boolean checkPresentAndInRange(
            List<PolicyDiagnostic> diagnostics,
            Short value,
            String path,
            String missingCode,
            String outOfRangeCode,
            short min,
            short max) {
        if (value == null) {
            diagnostics.add(new PolicyDiagnostic(
                    missingCode,
                    PolicySeverity.ERROR,
                    path,
                    path + " is missing; a policy without this threshold fails closed unpredictably "
                            + "at evaluation time instead of being rejected before activation."));
            return false;
        }
        if (value < min || value > max) {
            diagnostics.add(new PolicyDiagnostic(
                    outOfRangeCode,
                    PolicySeverity.ERROR,
                    path,
                    path + " (" + value + ") must be between " + min + " and " + max + "."));
            return false;
        }
        return true;
    }
}
