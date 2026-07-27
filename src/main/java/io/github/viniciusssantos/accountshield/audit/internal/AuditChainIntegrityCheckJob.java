package io.github.viniciusssantos.accountshield.audit.internal;

import io.github.viniciusssantos.accountshield.audit.AuditChainIntegrityFailed;
import io.github.viniciusssantos.accountshield.audit.AuditChainRootHash;
import io.github.viniciusssantos.accountshield.audit.AuditChainVerificationResult;
import io.github.viniciusssantos.accountshield.audit.AuditChainVerificationService;
import io.github.viniciusssantos.accountshield.audit.internal.persistence.AuditChainCheckpointStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Advances a checkpoint forward through {@code audit.decision_trace}'s hash chain in bounded
 * batches, verifying each batch is internally consistent and correctly linked to what came
 * before it. A detected break is alerted (metric + log + {@link AuditChainIntegrityFailed}
 * event) but the checkpoint is deliberately NOT advanced past it -- the break stays flagged on
 * every subsequent tick until an operator investigates, rather than silently marching past
 * corrupted history. This is a forward-only design: once a range verifies clean, it is not
 * re-checked later (see ADR 0027's Limitations section).
 */
@Component
public class AuditChainIntegrityCheckJob {

    private static final Logger log = LoggerFactory.getLogger(AuditChainIntegrityCheckJob.class);

    private final AuditChainVerificationService verificationService;
    private final AuditChainCheckpointStore checkpointStore;
    private final Clock clock;
    private final int batchSize;
    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher eventPublisher;

    public AuditChainIntegrityCheckJob(
            AuditChainVerificationService verificationService,
            AuditChainCheckpointStore checkpointStore,
            @Qualifier("decisionClock") Clock clock,
            @Value("${accountshield.audit.chain.verification.batch-size:500}") int batchSize,
            MeterRegistry meterRegistry,
            ApplicationEventPublisher eventPublisher) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.verificationService = verificationService;
        this.checkpointStore = checkpointStore;
        this.clock = clock;
        this.batchSize = batchSize;
        this.meterRegistry = meterRegistry;
        this.eventPublisher = eventPublisher;
        Gauge.builder("accountshield.audit.chain.checkpoint", checkpointStore,
                        AuditChainCheckpointStore::lastVerifiedSequence)
                .description("Last chain_sequence value the scheduled integrity check has verified clean")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${accountshield.audit.chain.verification.fixed-delay:1h}")
    @Transactional
    public void verifyNextBatch() {
        long lastVerified = checkpointStore.lastVerifiedSequence();
        long tipSequence = verificationService.currentRootHash().map(AuditChainRootHash::chainSequence).orElse(0L);
        if (tipSequence <= lastVerified) {
            return;
        }

        long from = lastVerified + 1;
        long to = Math.min(tipSequence, from + batchSize - 1);
        AuditChainVerificationResult result = verificationService.verifyRange(from, to);

        if (result.valid()) {
            checkpointStore.advanceTo(to, clock.instant());
            recordVerified("valid", result.recordsChecked());
        } else {
            recordVerified("broken", result.recordsChecked());
            log.error("audit_chain_integrity_broken from={} to={} breaks={}", from, to, result.breaks());
            eventPublisher.publishEvent(
                    new AuditChainIntegrityFailed(from, to, result.breaks(), clock.instant()));
        }
    }

    private void recordVerified(String outcome, long recordsChecked) {
        Counter.builder("accountshield.audit.chain.verified")
                .description("Total audit chain records verified by the scheduled integrity check, by outcome")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment(recordsChecked);
    }
}
