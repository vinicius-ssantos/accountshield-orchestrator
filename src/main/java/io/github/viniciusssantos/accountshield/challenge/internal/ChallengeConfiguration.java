package io.github.viniciusssantos.accountshield.challenge.internal;

import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ChallengeConfiguration {

    @Bean
    SimulatedChallengeProvider simulatedChallengeProvider() {
        return new SimulatedChallengeProvider(RandomGenerator.getDefault());
    }
}
