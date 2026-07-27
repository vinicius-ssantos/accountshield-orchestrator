package io.github.viniciusssantos.accountshield.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.PostgreSqlTestConfiguration;
import io.github.viniciusssantos.accountshield.benchmark.BenchmarkReport;
import io.github.viniciusssantos.accountshield.benchmark.BenchmarkStats;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignalEnvelope;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import io.github.viniciusssantos.accountshield.risk.SignalConfidence;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Issue #50, dimension 4 (outbox publish throughput). Placed inside {@code outbox.internal} (not
 * the {@code benchmark} package) because it needs direct {@link OutboxRelay} access -- the same
 * internal-module-test convention {@link OutboxReclaimAfterProcessFailureTest} already uses for
 * {@link OutboxClaimStore}. See ADR 0035.
 */
@Tag("benchmark")
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class OutboxPublishThroughputBenchmarkTest {

    private static final Path REPORT_PATH = Path.of("target/benchmark-reports/outbox-publish-benchmark.md");
    private static final int EVENTS_TO_PUBLISH = 150;
    private static final int DEFAULT_RELAY_BATCH_SIZE = 50;

    @Autowired
    private ProtectionDecisionService protectionDecisionService;

    @Autowired
    private OutboxRelay outboxRelay;

    @Test
    void outboxPublishThroughputBenchmark() throws Exception {
        // Every decide() call publishes exactly one ProtectionDecisionMade event onto the outbox
        // (OutboxEventRecorder) in the same transaction (ADR 0023) -- seeding EVENTS_TO_PUBLISH
        // pending rows to drain below.
        for (int i = 0; i < EVENTS_TO_PUBLISH; i++) {
            protectionDecisionService.decide(decisionCommand(i));
        }

        BenchmarkStats stats = new BenchmarkStats();
        int batchCalls = (int) Math.ceil((double) EVENTS_TO_PUBLISH / DEFAULT_RELAY_BATCH_SIZE);
        Instant start = Instant.now();
        for (int i = 0; i < batchCalls; i++) {
            long callStart = System.nanoTime();
            outboxRelay.dispatchPending();
            stats.record(System.nanoTime() - callStart);
        }
        Duration wallClock = Duration.between(start, Instant.now());

        BenchmarkReport report = new BenchmarkReport("AccountShield Outbox Publish Throughput Benchmark Report");
        report.addSection(stats.toMarkdownSection(
                "4. Outbox publish throughput",
                "`OutboxRelay.dispatchPending()` (default batch size " + DEFAULT_RELAY_BATCH_SIZE + ") draining "
                        + EVENTS_TO_PUBLISH + " pending rows seeded by " + EVENTS_TO_PUBLISH + " prior `decide()` "
                        + "calls, against the simulated publisher (ADR 0023) -- " + batchCalls + " batch call(s).",
                wallClock));
        report.writeTo(REPORT_PATH);

        assertThat(stats.errors()).isZero();
    }

    private ProtectionDecisionCommand decisionCommand(int index) {
        RiskSignalEnvelope envelope = new RiskSignalEnvelope(
                new RiskSignals(index % 4, index % 2 == 0, false, false, NetworkRiskLevel.LOW),
                "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true);
        return new ProtectionDecisionCommand(
                "benchmark-outbox-" + UUID.randomUUID() + "@example.test",
                ProtectionEventType.LOGIN_ATTEMPT,
                envelope,
                "idem-benchmark-outbox-" + UUID.randomUUID());
    }
}
