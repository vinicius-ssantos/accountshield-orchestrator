package io.github.viniciusssantos.accountshield.outbox.internal;

import io.github.viniciusssantos.accountshield.outbox.OutboxEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class OutboxConfiguration {

    @Bean
    @ConditionalOnMissingBean(OutboxEventPublisher.class)
    OutboxEventPublisher loggingOutboxEventPublisher() {
        return new LoggingOutboxEventPublisher();
    }
}
