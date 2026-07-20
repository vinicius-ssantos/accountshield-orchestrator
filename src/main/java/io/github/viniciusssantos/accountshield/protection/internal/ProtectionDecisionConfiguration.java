package io.github.viniciusssantos.accountshield.protection.internal;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ProtectionDecisionConfiguration {

    @Bean
    Clock decisionClock() {
        return Clock.systemUTC();
    }
}
