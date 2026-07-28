package io.github.viniciusssantos.accountshieldcli.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ScenarioDefinitionTest {

    @Test
    void allFiveAdrHandVerifiedScenariosArePresent() {
        assertThat(ScenarioDefinition.ALL).extracting(ScenarioDefinition::name).containsExactlyInAnyOrder(
                "credential-stuffing", "impossible-travel", "device-takeover", "mfa-fatigue", "recovery-abuse");
    }

    @Test
    void byNameFindsAKnownScenario() {
        ScenarioDefinition scenario = ScenarioDefinition.byName("impossible-travel");

        assertThat(scenario.expectedScore()).isEqualTo(60);
    }

    @Test
    void byNameThrowsAClearErrorForAnUnknownScenario() {
        assertThatThrownBy(() -> ScenarioDefinition.byName("not-a-real-scenario"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-a-real-scenario");
    }

    @Test
    void everyScenarioNameIsUnique() {
        assertThat(ScenarioDefinition.ALL.stream().map(ScenarioDefinition::name).distinct().count())
                .isEqualTo(ScenarioDefinition.ALL.size());
    }
}
