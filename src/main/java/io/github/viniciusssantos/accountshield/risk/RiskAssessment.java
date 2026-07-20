package io.github.viniciusssantos.accountshield.risk;

import java.util.List;
import java.util.Objects;

public record RiskAssessment(
        int score,
        RiskBand band,
        String algorithmVersion,
        List<RiskReason> reasons) {

    public RiskAssessment {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be between 0 and 100");
        }
        Objects.requireNonNull(band, "band must not be null");
        Objects.requireNonNull(algorithmVersion, "algorithmVersion must not be null");
        if (algorithmVersion.isBlank() || algorithmVersion.length() > 40) {
            throw new IllegalArgumentException("algorithmVersion must contain between 1 and 40 characters");
        }
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons must not be null"));
        int explainedScore = reasons.stream().mapToInt(RiskReason::contribution).sum();
        if (explainedScore != score) {
            throw new IllegalArgumentException("reason contributions must sum to score");
        }
        if (band != RiskBand.fromScore(score)) {
            throw new IllegalArgumentException("band must match score");
        }
    }
}
