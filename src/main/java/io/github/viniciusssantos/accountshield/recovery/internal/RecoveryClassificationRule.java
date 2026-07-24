package io.github.viniciusssantos.accountshield.recovery.internal;

import io.github.viniciusssantos.accountshield.recovery.RecoveryRiskClassification;

final class RecoveryClassificationRule {

    static final String VERSION = "recovery-classification-1.0";

    private static final int IMMEDIATE_THRESHOLD = 30;
    private static final int DELAYED_THRESHOLD = 60;

    private RecoveryClassificationRule() {
    }

    static RecoveryRiskClassification classify(int riskScore) {
        if (riskScore <= IMMEDIATE_THRESHOLD) {
            return RecoveryRiskClassification.IMMEDIATE;
        }
        if (riskScore <= DELAYED_THRESHOLD) {
            return RecoveryRiskClassification.DELAYED;
        }
        return RecoveryRiskClassification.MANUAL_REVIEW;
    }

    static boolean isKnownVersion(String version) {
        return VERSION.equals(version);
    }
}
