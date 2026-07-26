package io.github.viniciusssantos.accountshield.recovery.internal;

import io.github.viniciusssantos.accountshield.recovery.internal.persistence.RecoveryAuthorizationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RecoveryAuthorizationRetentionCleanup {

    private static final Logger log = LoggerFactory.getLogger(RecoveryAuthorizationRetentionCleanup.class);
    private static final String JOB_NAME = "recovery_authorization";

    private final RecoveryAuthorizationRepository repository;
    private final Clock clock;
    private final Duration expiredTtl;
    private final MeterRegistry meterRegistry;

    public RecoveryAuthorizationRetentionCleanup(
            RecoveryAuthorizationRepository repository,
            @Qualifier("decisionClock") Clock clock,
            @Value("${accountshield.recovery.authorization-retention.expired-ttl:30d}") Duration expiredTtl,
            MeterRegistry meterRegistry) {
        if (expiredTtl.isNegative() || expiredTtl.isZero()) {
            throw new IllegalArgumentException("expiredTtl must be positive");
        }
        this.repository = repository;
        this.clock = clock;
        this.expiredTtl = expiredTtl;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${accountshield.recovery.authorization-retention.fixed-delay:1h}")
    @Transactional
    public void purgeExpiredAuthorizations() {
        Instant cutoff = clock.instant().minus(expiredTtl);
        int deleted = repository.deleteExpiredAndUnreferenced(cutoff);
        if (deleted > 0) {
            log.info("recovery_authorization_retention_purged count={} cutoff={}", deleted, cutoff);
        }
        Counter.builder("accountshield.retention.purged")
                .description("Total rows purged by a retention cleanup job")
                .tag("job", JOB_NAME)
                .register(meterRegistry)
                .increment(deleted);
    }
}
