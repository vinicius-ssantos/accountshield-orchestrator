package io.github.viniciusssantos.accountshield.challenge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

class SimulationModeInfoContributorTest {

    @Test
    void reportsSimulationEnabled() {
        SimulationModeInfoContributor contributor = new SimulationModeInfoContributor(true);
        Info.Builder builder = new Info.Builder();

        contributor.contribute(builder);

        Object challengeProviders = builder.build().get("challengeProviders");
        assertThat(challengeProviders).isEqualTo(java.util.Map.of("simulated", true));
    }

    @Test
    void reportsSimulationDisabled() {
        SimulationModeInfoContributor contributor = new SimulationModeInfoContributor(false);
        Info.Builder builder = new Info.Builder();

        contributor.contribute(builder);

        Object challengeProviders = builder.build().get("challengeProviders");
        assertThat(challengeProviders).isEqualTo(java.util.Map.of("simulated", false));
    }
}
