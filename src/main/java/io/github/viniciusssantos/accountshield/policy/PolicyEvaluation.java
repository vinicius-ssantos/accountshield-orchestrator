package io.github.viniciusssantos.accountshield.policy;

import java.util.Objects;

public record PolicyEvaluation(
        String policyKey,
        String policyVersion,
        ProtectionOutcome outcome) {

    public PolicyEvaluation {
        Objects.requireNonNull(policyKey, "policyKey must not be null");
        Objects.requireNonNull(policyVersion, "policyVersion must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (policyKey.isBlank() || policyKey.length() > 100) {
            throw new IllegalArgumentException("policyKey must contain between 1 and 100 characters");
        }
        if (policyVersion.isBlank() || policyVersion.length() > 40) {
            throw new IllegalArgumentException("policyVersion must contain between 1 and 40 characters");
        }
    }
}
