package io.github.viniciusssantos.accountshield.challenge.internal;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
class SimulationModeGuard {

    static final String PRODUCTION_PROFILE = "production";

    private final Environment environment;
    private final boolean simulationEnabled;

    SimulationModeGuard(
            Environment environment,
            @Value("${accountshield.challenge.simulation-enabled:true}") boolean simulationEnabled) {
        this.environment = environment;
        this.simulationEnabled = simulationEnabled;
    }

    @PostConstruct
    void verifyNotSimulatingInProduction() {
        if (simulationEnabled && environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILE))) {
            throw new IllegalStateException(
                    "Simulated challenge providers cannot run with the '" + PRODUCTION_PROFILE
                            + "' profile active. Set accountshield.challenge.simulation-enabled=false "
                            + "and configure real challenge providers before enabling this profile.");
        }
    }
}
