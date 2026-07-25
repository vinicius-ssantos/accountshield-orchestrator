package io.github.viniciusssantos.accountshield.protection.internal;

import io.github.viniciusssantos.accountshield.protection.internal.persistence.IdempotencyRecordRepository;
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

    private final IdempotencyRecordRepository repository;
    private final Clock clock;
    private final int batchSize;

    public IdempotencyRecordRetentionCleanup(
            IdempotencyRecordRepository repository,
            @Qualifier("decisionClock") Clock clock,
            @Value("${accountshield.protection.idempotency.retention.batch-size:500}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.repository = repository;
        this.clock = clock;
        this.batchSize = batchSize;
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
    }
}
