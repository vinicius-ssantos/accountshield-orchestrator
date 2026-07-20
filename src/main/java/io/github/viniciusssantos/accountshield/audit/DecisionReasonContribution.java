package io.github.viniciusssantos.accountshield.audit;

import java.util.Map;
import java.util.Objects;

public record DecisionReasonContribution(
        String code,
        int contribution,
        Map<String, Object> details) {

    public DecisionReasonContribution {
        Objects.requireNonNull(code, "code must not be null");
        if (code.isBlank() || code.length() > 64) {
            throw new IllegalArgumentException("code must contain between 1 and 64 characters");
        }
        if (contribution < -100 || contribution > 100) {
            throw new IllegalArgumentException("contribution must be between -100 and 100");
        }
        details = Map.copyOf(Objects.requireNonNull(details, "details must not be null"));
    }
}
