package io.github.viniciusssantos.accountshield.outbox.internal;

import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventRepository;
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
public class OutboxEventRetentionCleanup {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventRetentionCleanup.class);
    private static final int MAX_BATCHES_PER_TICK = 100;

    private final OutboxEventRepository repository;
    private final Clock clock;
    private final Duration publishedTtl;
    private final Duration deadLetteredTtl;
    private final int batchSize;

    public OutboxEventRetentionCleanup(
            OutboxEventRepository repository,
            @Qualifier("decisionClock") Clock clock,
            @Value("${accountshield.outbox.retention.published-ttl:7d}") Duration publishedTtl,
            @Value("${accountshield.outbox.retention.dead-lettered-ttl:30d}") Duration deadLetteredTtl,
            @Value("${accountshield.outbox.retention.batch-size:500}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.repository = repository;
        this.clock = clock;
        this.publishedTtl = publishedTtl;
        this.deadLetteredTtl = deadLetteredTtl;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${accountshield.outbox.retention.fixed-delay:1h}")
    @Transactional
    public void purgeExpiredRecords() {
        Instant now = clock.instant();
        int publishedDeleted = purgeBatches(repository::deletePublishedBatch, now.minus(publishedTtl));
        int deadLetteredDeleted = purgeBatches(repository::deleteDeadLetteredBatch, now.minus(deadLetteredTtl));
        if (publishedDeleted > 0 || deadLetteredDeleted > 0) {
            log.info(
                    "outbox_event_retention_purged published_count={} dead_lettered_count={} now={}",
                    publishedDeleted, deadLetteredDeleted, now);
        }
    }

    private int purgeBatches(BatchDeleter deleter, Instant cutoff) {
        int totalDeleted = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_TICK; batch++) {
            int deleted = deleter.deleteBatch(cutoff, batchSize);
            totalDeleted += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return totalDeleted;
    }

    @FunctionalInterface
    private interface BatchDeleter {
        int deleteBatch(Instant cutoff, int batchSize);
    }
}
