package io.github.viniciusssantos.accountshield.investigation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Composes the audit evidence port and the side-effect-free replay engine into one deterministic,
 * privacy-minimized comparison for an operator console.
 */
public interface DecisionReplayQuery {

    Optional<DecisionReplayComparison> replay(String decisionReference);

    record ReasonEvidence(String code, int contribution) {
        public ReasonEvidence {
            Objects.requireNonNull(code, "code must not be null");
            if (code.isBlank() || code.length() > 64) {
                throw new IllegalArgumentException("code must contain between 1 and 64 characters");
            }
        }
    }

    record DecisionReplaySide(
            String outcome,
            int riskScore,
            String riskBand,
            List<ReasonEvidence> reasons) {

        public DecisionReplaySide {
            outcome = requireText(outcome, "outcome");
            riskBand = requireText(riskBand, "riskBand");
            reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons must not be null"));
        }
    }

    record DecisionReplayComparison(
            String decisionReference,
            String maskedSubjectReference,
            boolean matches,
            DecisionReplaySide original,
            DecisionReplaySide replayed,
            String policyKey,
            String policyVersion,
            String algorithmVersion,
            String normalizedInputSchemaVersion,
            String reasonCatalogVersion,
            String decisionEngineVersion,
            List<String> mismatches) {

        public DecisionReplayComparison {
            decisionReference = requireText(decisionReference, "decisionReference");
            maskedSubjectReference = requireText(maskedSubjectReference, "maskedSubjectReference");
            Objects.requireNonNull(original, "original must not be null");
            Objects.requireNonNull(replayed, "replayed must not be null");
            policyKey = requireText(policyKey, "policyKey");
            policyVersion = requireText(policyVersion, "policyVersion");
            algorithmVersion = requireText(algorithmVersion, "algorithmVersion");
            reasonCatalogVersion = requireText(reasonCatalogVersion, "reasonCatalogVersion");
            decisionEngineVersion = requireText(decisionEngineVersion, "decisionEngineVersion");
            mismatches = List.copyOf(Objects.requireNonNull(mismatches, "mismatches must not be null"));
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
