package io.github.viniciusssantos.accountshield.challenge.internal;

import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.internal.persistence.ChallengePlanRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
public class ChallengePlanRetentionCleanup {

    private static final Logger log = LoggerFactory.getLogger(ChallengePlanRetentionCleanup.class);
    private static final String JOB_NAME = "challenge_plan";

    private static final List<String> TERMINAL_STATUSES = Arrays.stream(ChallengeStatus.values())
            .filter(ChallengeStatus::isTerminal)
            .map(Enum::name)
            .toList();

    private final ChallengePlanRepository repository;
    private final Clock clock;
    private final Duration terminalTtl;
    private final MeterRegistry meterRegistry;

    public ChallengePlanRetentionCleanup(
            ChallengePlanRepository repository,
            @Qualifier("decisionClock") Clock clock,
            @Value("${accountshield.challenge.retention.terminal-ttl:1d}") Duration terminalTtl,
            MeterRegistry meterRegistry) {
        if (terminalTtl.isNegative() || terminalTtl.isZero()) {
            throw new IllegalArgumentException("terminalTtl must be positive");
        }
        this.repository = repository;
        this.clock = clock;
        this.terminalTtl = terminalTtl;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${accountshield.challenge.retention.fixed-delay:1h}")
    @Transactional
    public void purgeExpiredTerminalChallenges() {
        Instant cutoff = clock.instant().minus(terminalTtl);
        int deleted = repository.deleteByStatusInAndExpiresAtBefore(TERMINAL_STATUSES, cutoff);
        if (deleted > 0) {
            log.info("challenge_plan_retention_purged count={} cutoff={}", deleted, cutoff);
        }
        Counter.builder("accountshield.retention.purged")
                .description("Total rows purged by a retention cleanup job")
                .tag("job", JOB_NAME)
                .register(meterRegistry)
                .increment(deleted);
    }
}
