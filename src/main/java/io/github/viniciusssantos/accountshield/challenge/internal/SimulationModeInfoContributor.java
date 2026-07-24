package io.github.viniciusssantos.accountshield.challenge.internal;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

@Component
class SimulationModeInfoContributor implements InfoContributor {

    private final boolean simulationEnabled;

    SimulationModeInfoContributor(
            @Value("${accountshield.challenge.simulation-enabled:true}") boolean simulationEnabled) {
        this.simulationEnabled = simulationEnabled;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("challengeProviders", Map.of("simulated", simulationEnabled));
    }
}
