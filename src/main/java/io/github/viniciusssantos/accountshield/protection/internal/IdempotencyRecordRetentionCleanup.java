package io.github.viniciusssantos.accountshield.protection.internal;

import io.github.viniciusssantos.accountshield.protection.internal.persistence.IdempotencyRecordRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IdempotencyRecordRetentionCleanup {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRecordRetentionCleanup.class);
    private static final int MAX_BATCHES_PER_TICK = 100;
    private static final String JOB_NAME = "idempotency_record";

    private final IdempotencyRecordRepository repository;
    private final Clock clock;
    private final int batchSize;
    private final MeterRegistry meterRegistry;

    public IdempotencyRecordRetentionCleanup(
            IdempotencyRecordRepository repository,
            @Qualifier("decisionClock") Clock clock,
            @Value("${accountshield.protection.idempotency.retention.batch-size:500}") int batchSize,
            MeterRegistry meterRegistry) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.repository = repository;
        this.clock = clock;
        this.batchSize = batchSize;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${accountshield.protection.idempotency.retention.fixed-delay:1h}")
    @Transactional
    public void purgeExpiredRecords() {
        Instant cutoff = clock.instant();
        int totalDeleted = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_TICK; batch++) {
            int deleted = repository.deleteExpiredBatch(cutoff, batchSize);
            totalDeleted += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        if (totalDeleted > 0) {
            log.info("idempotency_record_retention_purged count={} cutoff={}", totalDeleted, cutoff);
        }
        Counter.builder("accountshield.retention.purged")
                .description("Total rows purged by a retention cleanup job")
                .tag("job", JOB_NAME)
                .register(meterRegistry)
                .increment(totalDeleted);
    }
}
