package io.github.viniciusssantos.accountshield.risk;

public enum RiskBand {
    LOW,
    MEDIUM,
    HIGH;

    public static RiskBand fromScore(int score) {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be between 0 and 100");
        }
        if (score >= 70) {
            return HIGH;
        }
        if (score >= 30) {
            return MEDIUM;
        }
        return LOW;
    }
}
