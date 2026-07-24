package io.github.viniciusssantos.accountshield.recovery.internal;

import io.github.viniciusssantos.accountshield.recovery.RecoveryStatus;
import io.github.viniciusssantos.accountshield.recovery.internal.persistence.RecoveryFlowRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RecoveryFlowRetentionCleanup {

    private static final Logger log = LoggerFactory.getLogger(RecoveryFlowRetentionCleanup.class);

    private static final List<String> TERMINAL_STATUSES = Arrays.stream(RecoveryStatus.values())
            .filter(RecoveryStatus::isTerminal)
            .map(Enum::name)
            .toList();

    private final RecoveryFlowRepository repository;
    private final Clock clock;
    private final Duration terminalTtl;

    public RecoveryFlowRetentionCleanup(
            RecoveryFlowRepository repository,
            @Qualifier("decisionClock") Clock clock,
            @Value("${accountshield.recovery.retention.terminal-ttl:30d}") Duration terminalTtl) {
        if (terminalTtl.isNegative() || terminalTtl.isZero()) {
            throw new IllegalArgumentException("terminalTtl must be positive");
        }
        this.repository = repository;
        this.clock = clock;
        this.terminalTtl = terminalTtl;
    }

    @Scheduled(fixedDelayString = "${accountshield.recovery.retention.fixed-delay:1h}")
    @Transactional
    public void purgeExpiredTerminalFlows() {
        Instant cutoff = clock.instant().minus(terminalTtl);
        int deleted = repository.deleteByStatusInAndUpdatedAtBefore(TERMINAL_STATUSES, cutoff);
        if (deleted > 0) {
            log.info("recovery_flow_retention_purged count={} cutoff={}", deleted, cutoff);
        }
    }
}
