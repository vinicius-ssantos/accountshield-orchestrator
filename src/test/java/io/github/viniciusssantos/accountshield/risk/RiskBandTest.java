package io.github.viniciusssantos.accountshield.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RiskBandTest {

    @ParameterizedTest
    @CsvSource({
            "0, LOW",
            "1, LOW",
            "29, LOW",
            "30, MEDIUM",
            "31, MEDIUM",
            "69, MEDIUM",
            "70, HIGH",
            "71, HIGH",
            "100, HIGH"
    })
    void classifiesScoreIntoCorrectBand(int score, RiskBand expected) {
        assertThat(RiskBand.fromScore(score)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -100, 101, 200})
    void rejectsScoresOutsideValidRange(int invalidScore) {
        assertThatThrownBy(() -> RiskBand.fromScore(invalidScore))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("score must be between 0 and 100");
    }

    @Test
    void boundaryThirtyIsMediumNotLow() {
        assertThat(RiskBand.fromScore(29)).isEqualTo(RiskBand.LOW);
        assertThat(RiskBand.fromScore(30)).isEqualTo(RiskBand.MEDIUM);
    }

    @Test
    void boundarySeventyIsHighNotMedium() {
        assertThat(RiskBand.fromScore(69)).isEqualTo(RiskBand.MEDIUM);
        assertThat(RiskBand.fromScore(70)).isEqualTo(RiskBand.HIGH);
    }
}
