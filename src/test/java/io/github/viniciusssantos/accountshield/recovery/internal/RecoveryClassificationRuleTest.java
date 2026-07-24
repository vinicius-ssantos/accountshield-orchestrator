package io.github.viniciusssantos.accountshield.recovery.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.recovery.RecoveryRiskClassification;
import org.junit.jupiter.api.Test;

class RecoveryClassificationRuleTest {

    @Test
    void scoreZeroIsImmediate() {
        assertThat(RecoveryClassificationRule.classify(0)).isEqualTo(RecoveryRiskClassification.IMMEDIATE);
    }

    @Test
    void immediateUpperBoundaryIsThirty() {
        assertThat(RecoveryClassificationRule.classify(30)).isEqualTo(RecoveryRiskClassification.IMMEDIATE);
    }

    @Test
    void delayedLowerBoundaryIsThirtyOne() {
        assertThat(RecoveryClassificationRule.classify(31)).isEqualTo(RecoveryRiskClassification.DELAYED);
    }

    @Test
    void delayedUpperBoundaryIsSixty() {
        assertThat(RecoveryClassificationRule.classify(60)).isEqualTo(RecoveryRiskClassification.DELAYED);
    }

    @Test
    void manualReviewLowerBoundaryIsSixtyOne() {
        assertThat(RecoveryClassificationRule.classify(61)).isEqualTo(RecoveryRiskClassification.MANUAL_REVIEW);
    }

    @Test
    void scoreOneHundredIsManualReview() {
        assertThat(RecoveryClassificationRule.classify(100)).isEqualTo(RecoveryRiskClassification.MANUAL_REVIEW);
    }

    @Test
    void currentVersionIsKnown() {
        assertThat(RecoveryClassificationRule.isKnownVersion(RecoveryClassificationRule.VERSION)).isTrue();
    }

    @Test
    void unrecognizedVersionIsNotKnown() {
        assertThat(RecoveryClassificationRule.isKnownVersion("recovery-classification-0.9")).isFalse();
        assertThat(RecoveryClassificationRule.isKnownVersion(null)).isFalse();
    }
}
