package io.github.viniciusssantos.accountshield.protection.internal.persistence;

import io.github.viniciusssantos.accountshield.PostgreSqlTestConfiguration;
import io.github.viniciusssantos.accountshield.benchmark.BenchmarkReport;
import io.github.viniciusssantos.accountshield.benchmark.BenchmarkStats;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Issue #50, dimensions 3 (persistence latency) and 6 (database growth and index impact). Placed
 * inside {@code protection.internal.persistence} (rather than the {@code benchmark} package, like
 * {@code CapacityBenchmarkTest}) because it needs direct {@link ProtectionRequestRepository} access
 * -- matching this codebase's existing convention of internal-module benchmark/fault-injection
 * tests living inside that module's own {@code internal} test package (see e.g.
 * {@code OutboxReclaimAfterProcessFailureTest}), rather than exposing repository internals outside
 * the module. See ADR 0035.
 */
@Tag("benchmark")
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class PersistenceLatencyBenchmarkTest {

    private static final Path REPORT_PATH =
            Path.of("target/benchmark-reports/persistence-and-growth-benchmark.md");
    private static final BenchmarkReport REPORT =
            new BenchmarkReport("AccountShield Persistence & Database Growth Benchmark Report");

    // Cumulative row-count tiers: small enough to keep the nightly run bounded, large enough to
    // show a real growth trend for an indexed primary-key lookup.
    private static final int[] GROWTH_TIERS = {500, 2_000, 8_000};
    private static final int LOOKUPS_PER_TIER = 100;

    @Autowired
    private ProtectionRequestRepository protectionRequestRepository;

    @AfterAll
    static void writeReport() throws Exception {
        REPORT.writeTo(REPORT_PATH);
    }

    @Test
    void persistenceAndGrowthBenchmarkSuite() {
        benchmarkRawPersistenceLatency();
        benchmarkDatabaseGrowthAndIndexImpact();
    }

    private void benchmarkRawPersistenceLatency() {
        BenchmarkStats stats = new BenchmarkStats();
        Instant start = Instant.now();
        for (int i = 0; i < 200; i++) {
            long callStart = System.nanoTime();
            protectionRequestRepository.saveAndFlush(newEntity("persist-" + i));
            stats.record(System.nanoTime() - callStart);
        }
        Duration wallClock = Duration.between(start, Instant.now());
        REPORT.addSection(stats.toMarkdownSection(
                "3. Raw persistence latency",
                "`ProtectionRequestRepository.saveAndFlush(...)` for a single new row, outside the full "
                        + "`decide()` pipeline -- isolates raw single-row insert cost (including the "
                        + "envelope-encryption `account_reference` converter, ADR 0025).",
                wallClock));
    }

    private void benchmarkDatabaseGrowthAndIndexImpact() {
        StringBuilder section = new StringBuilder();
        section.append("### 6. Database growth and index impact\n\n");
        section.append("Primary-key point lookup (`findById`) latency as `protection.protection_request` grows, "
                + "seeded cumulatively via `saveAndFlush` in this run. A flat p95 across tiers demonstrates the "
                + "primary-key index continues to bound lookup cost as the table grows; a rising p95 would "
                + "identify a real, measured bottleneck.\n\n");
        section.append("| Row count tier | p50 | p95 | p99 | Mean |\n|---|---|---|---|---|\n");

        int seededSoFar = 0;
        for (int tier : GROWTH_TIERS) {
            int toSeed = tier - seededSoFar;
            UUID markerIdForThisTier = null;
            for (int i = 0; i < toSeed; i++) {
                UUID id = UUID.randomUUID();
                protectionRequestRepository.saveAndFlush(newEntity(id, "growth-" + seededSoFar + "-" + i));
                if (markerIdForThisTier == null) {
                    markerIdForThisTier = id;
                }
            }
            seededSoFar = tier;

            BenchmarkStats lookupStats = new BenchmarkStats();
            for (int i = 0; i < LOOKUPS_PER_TIER; i++) {
                long callStart = System.nanoTime();
                protectionRequestRepository.findById(markerIdForThisTier).orElseThrow();
                lookupStats.record(System.nanoTime() - callStart);
            }
            section.append(String.format(
                    Locale.ROOT,
                    "| %,d rows | %.2f ms | %.2f ms | %.2f ms | %.2f ms |%n",
                    seededSoFar, lookupStats.p50Millis(), lookupStats.p95Millis(),
                    lookupStats.p99Millis(), lookupStats.meanMillis()));
        }
        section.append('\n');
        REPORT.addSection(section.toString());
    }

    private ProtectionRequestEntity newEntity(String slug) {
        return newEntity(UUID.randomUUID(), slug);
    }

    private ProtectionRequestEntity newEntity(UUID id, String slug) {
        return new ProtectionRequestEntity(
                id,
                "default-client",
                "benchmark-" + slug + "-" + UUID.randomUUID() + "@example.test",
                "LOGIN_ATTEMPT",
                UUID.randomUUID().toString().replace("-", ""),
                "RECEIVED",
                Instant.now());
    }
}
