package io.github.viniciusssantos.accountshield.challenge.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SimulationModeGuardTest {

    @Test
    void failsFastWhenProductionProfileHasSimulationEnabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(SimulationModeGuard.PRODUCTION_PROFILE);
        SimulationModeGuard guard = new SimulationModeGuard(environment, true);

        assertThatThrownBy(guard::verifyNotSimulatingInProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production");
    }

    @Test
    void allowsProductionProfileWhenSimulationIsDisabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(SimulationModeGuard.PRODUCTION_PROFILE);
        SimulationModeGuard guard = new SimulationModeGuard(environment, false);

        assertThatCode(guard::verifyNotSimulatingInProduction).doesNotThrowAnyException();
    }

    @Test
    void allowsLocalProfileWithSimulationEnabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        SimulationModeGuard guard = new SimulationModeGuard(environment, true);

        assertThatCode(guard::verifyNotSimulatingInProduction).doesNotThrowAnyException();
    }

    @Test
    void allowsTheDefaultProfileWithSimulationEnabled() {
        MockEnvironment environment = new MockEnvironment();
        SimulationModeGuard guard = new SimulationModeGuard(environment, true);

        assertThatCode(guard::verifyNotSimulatingInProduction).doesNotThrowAnyException();
    }
}
