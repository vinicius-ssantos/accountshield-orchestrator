package io.github.viniciusssantos.accountshield.risk;

import java.util.Objects;

public record RiskReason(String code, int contribution) {

    public RiskReason {
        Objects.requireNonNull(code, "code must not be null");
        if (code.isBlank() || code.length() > 64) {
            throw new IllegalArgumentException("code must contain between 1 and 64 characters");
        }
        if (contribution <= 0 || contribution > 100) {
            throw new IllegalArgumentException("contribution must be between 1 and 100");
        }
    }
}
