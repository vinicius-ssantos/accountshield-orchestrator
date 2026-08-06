package io.github.viniciusssantos.accountshield.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.PostgreSqlTestConfiguration;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Issue #50, dimension 7 (connection-pool saturation) -- this is the benchmark suite's required
 * "at least one measured bottleneck" (roadmap.md Gate 7's acceptance criteria and issue #50's
 * acceptance criteria both name this explicitly). Runs its own {@code @SpringBootTest} context
 * (deliberately not sharing {@link CapacityBenchmarkTest}'s) because it needs its own shrunk Hikari
 * pool via {@code @DynamicPropertySource}, following the exact per-test datasource-property-
 * override pattern {@code DatabaseLatencyResilienceTest} already established for Toxiproxy (ADR
 * 0032).
 *
 * <p>The pool is intentionally shrunk far below default (3 vs. the default 10,
 * {@code spring.datasource.hikari.maximum-pool-size}) and driven with concurrency well above it, so
 * queueing for a connection is expected and safe: default {@code connection-timeout} (5s, from
 * application.yml) leaves generous headroom over this workload's actual per-call latency, so the
 * bottleneck shows up as measured wait-time growth, not flaky connection-acquisition failures.
 */
@Tag("benchmark")
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class ConnectionPoolSaturationTest {

    private static final Path REPORT_PATH = Path.of("target/benchmark-reports/connection-pool-saturation.md");
    private static final int SATURATED_POOL_SIZE = 3;
    private static final int UNSATURATED_CONCURRENCY = SATURATED_POOL_SIZE;
    private static final int SATURATED_CONCURRENCY = SATURATED_POOL_SIZE * 4;
    private static final int CALLS_PER_PHASE = 30;

    @Autowired
    private ProtectionDecisionService protectionDecisionService;

    @DynamicPropertySource
    static void shrinkConnectionPool(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> SATURATED_POOL_SIZE);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 1);
    }

    @Test
    void connectionPoolSaturationBenchmark() throws Exception {
        Instant unsaturatedStart = Instant.now();
        BenchmarkStats unsaturated = runConcurrentDecisions(UNSATURATED_CONCURRENCY, "baseline");
        Duration unsaturatedWallClock = Duration.between(unsaturatedStart, Instant.now());

        Instant saturatedStart = Instant.now();
        BenchmarkStats saturated = runConcurrentDecisions(SATURATED_CONCURRENCY, "saturated");
        Duration saturatedWallClock = Duration.between(saturatedStart, Instant.now());

        BenchmarkReport report = new BenchmarkReport("AccountShield Connection-Pool Saturation Benchmark Report");
        report.addSection(unsaturated.toMarkdownSection(
                "7a. Baseline (concurrency == pool size, no queueing expected)",
                "Concurrency " + UNSATURATED_CONCURRENCY + " against a Hikari pool of "
                        + SATURATED_POOL_SIZE + " connections (`spring.datasource.hikari.maximum-pool-size`).",
                unsaturatedWallClock));
        report.addSection(saturated.toMarkdownSection(
                "7b. Saturated (concurrency " + SATURATED_CONCURRENCY + " = "
                        + (SATURATED_CONCURRENCY / SATURATED_POOL_SIZE) + "x pool size)",
                "Same pool of " + SATURATED_POOL_SIZE + " connections under " + SATURATED_CONCURRENCY
                        + "x concurrency -- the measured bottleneck: connection-acquisition queueing pushes latency "
                        + "up, bounded only by the default 5s `connection-timeout` (no errors expected below that "
                        + "bound at this call volume).",
                saturatedWallClock));
        report.addSection("**Bottleneck interpretation:** median (p50) latency is the more reliable signal at this "
                + "sample size (tail percentiles are noisy with only " + CALLS_PER_PHASE + " calls per phase) --"
                + " comparing 7a's p50 (" + String.format(Locale.ROOT, "%.2f", unsaturated.p50Millis())
                + " ms) to 7b's p50 (" + String.format(Locale.ROOT, "%.2f", saturated.p50Millis())
                + " ms) isolates the latency cost of connection-pool contention directly attributable to pool size, "
                + "independent of any other change in the request itself. The full p50/p95/p99 tables above are "
                + "reported regardless so a reader can judge tail behavior for themselves.\n\n");
        report.writeTo(REPORT_PATH);

        assertThat(unsaturated.errors()).isZero();
        assertThat(saturated.errors()).isZero();
    }

    private BenchmarkStats runConcurrentDecisions(int concurrency, String slug) throws Exception {
        BenchmarkStats stats = new BenchmarkStats();
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<?>> futures = new ArrayList<>(CALLS_PER_PHASE);
            for (int i = 0; i < CALLS_PER_PHASE; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    long callStart = System.nanoTime();
                    try {
                        protectionDecisionService.decide(decisionCommand(slug, index));
                        stats.record(System.nanoTime() - callStart);
                    } catch (RuntimeException ex) {
                        stats.recordError();
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception ex) {
                    stats.recordError();
                }
            }
        } finally {
            executor.shutdown();
        }
        return stats;
    }

    private ProtectionDecisionCommand decisionCommand(String slug, int index) {
        RiskSignalEnvelope envelope = new RiskSignalEnvelope(
                new RiskSignals(index % 4, index % 2 == 0, false, false, NetworkRiskLevel.LOW),
                "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true);
        return new ProtectionDecisionCommand(
                "benchmark-pool-" + slug + "-" + UUID.randomUUID() + "@example.test",
                ProtectionEventType.LOGIN_ATTEMPT,
                envelope,
                "idem-benchmark-pool-" + slug + "-" + UUID.randomUUID());
    }
}
