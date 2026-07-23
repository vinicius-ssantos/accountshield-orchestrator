package io.github.viniciusssantos.accountshield.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DecisionReasonContributionTest {

    @Test
    void createsValidContribution() {
        DecisionReasonContribution contribution = new DecisionReasonContribution("NEW_DEVICE", 20, Map.of("device", "mobile"));

        assertThat(contribution.code()).isEqualTo("NEW_DEVICE");
        assertThat(contribution.contribution()).isEqualTo(20);
        assertThat(contribution.details()).containsEntry("device", "mobile");
    }

    @Test
    void rejectsNullCode() {
        assertThatThrownBy(() -> new DecisionReasonContribution(null, 10, Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("code");
    }

    @Test
    void rejectsBlankCode() {
        assertThatThrownBy(() -> new DecisionReasonContribution("", 10, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");
    }

    @Test
    void rejectsOversizedCode() {
        assertThatThrownBy(() -> new DecisionReasonContribution("x".repeat(65), 10, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");
    }

    @Test
    void rejectsContributionBelowNegativeHundred() {
        assertThatThrownBy(() -> new DecisionReasonContribution("CODE", -101, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contribution");
    }

    @Test
    void rejectsContributionAboveHundred() {
        assertThatThrownBy(() -> new DecisionReasonContribution("CODE", 101, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contribution");
    }

    @Test
    void acceptsContributionBoundaries() {
        assertThat(new DecisionReasonContribution("CODE", -100, Map.of()).contribution()).isEqualTo(-100);
        assertThat(new DecisionReasonContribution("CODE", 100, Map.of()).contribution()).isEqualTo(100);
    }

    @Test
    void rejectsNullDetails() {
        assertThatThrownBy(() -> new DecisionReasonContribution("CODE", 10, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("details");
    }

    @Test
    void defensivelyCopiesDetails() {
        var mutable = new java.util.HashMap<String, Object>();
        mutable.put("key", "value");

        DecisionReasonContribution contribution = new DecisionReasonContribution("CODE", 10, mutable);

        mutable.put("injected", "evil");

        assertThat(contribution.details()).hasSize(1).doesNotContainKey("injected");
    }
}
