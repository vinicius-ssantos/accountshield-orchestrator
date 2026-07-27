package io.github.viniciusssantos.accountshield.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.PostgreSqlTestConfiguration;
import io.github.viniciusssantos.accountshield.audit.AuditChainRecordProof;
import io.github.viniciusssantos.accountshield.audit.AuditChainRootHash;
import io.github.viniciusssantos.accountshield.audit.AuditChainVerificationResult;
import io.github.viniciusssantos.accountshield.audit.AuditChainVerificationService;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationService;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignalEnvelope;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import io.github.viniciusssantos.accountshield.risk.SignalConfidence;
import io.github.viniciusssantos.accountshield.simulation.ReplayResult;
import io.github.viniciusssantos.accountshield.simulation.SimulationService;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Issue #50: reproducible capacity/performance benchmarks for the core decision pipeline, run
 * against the real Spring context and a real Postgres instance (Testcontainers) -- no mocks, no
 * hardcoded numbers. Every measurement is a real wall-clock sample from this run; there is no
 * baseline this codebase has ever published before, so nothing here asserts a hard latency/
 * throughput threshold (matching ADR 0031's "report, don't gate" stance for JaCoCo coverage before
 * a baseline exists). See ADR 0035 for the full methodology and which of issue #50's 8 named
 * dimensions live in this class vs. the sibling benchmark test classes (persistence/DB-growth in
 * the {@code protection.internal.persistence} package, outbox throughput in {@code outbox.internal}
 * -- both need direct repository/relay access that only exists inside those modules' own internal
 * packages, matching this codebase's existing internal-test-access convention) vs. connection-pool
 * saturation ({@link ConnectionPoolSaturationTest}, which needs its own shrunk-pool Spring context).
 *
 * <p>{@code @Tag("benchmark")}: excluded from the default CI gate (ci.yml) the same way
 * {@code @Tag("resilience")} is (ADR 0032); runs in full every night (nightly.yml). A separate,
 * untagged {@link CapacitySmokeBenchmarkTest} runs a tiny slice of this same harness in the default
 * gate with no hard thresholds, satisfying "CI runs a smoke benchmark without flaky hard
 * thresholds."
 */
@Tag("benchmark")
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class CapacityBenchmarkTest {

    private static final Path REPORT_PATH = Path.of("target/benchmark-reports/capacity-benchmark.md");
    private static final BenchmarkReport REPORT = new BenchmarkReport("AccountShield Capacity Benchmark Report");

    private static final int SEQUENTIAL_DECISIONS = 150;
    private static final int CONCURRENT_DECISIONS = 100;
    private static final int CONCURRENCY = 8;
    private static final int POLICY_EVAL_ITERATIONS = 200;
    private static final int REPLAY_SAMPLE = 50;

    @Autowired
    private ProtectionDecisionService protectionDecisionService;

    @Autowired
    private PolicyEvaluationService policyEvaluationService;

    @Autowired
    private SimulationService simulationService;

    @Autowired
    private AuditChainVerificationService auditChainVerificationService;

    @AfterAll
    static void writeReport() throws Exception {
        REPORT.writeTo(REPORT_PATH);
    }

    @Test
    void capacityBenchmarkSuite() {
        List<ProtectionDecisionResult> sequentialDecisions = benchmarkSequentialDecisionThroughput();
        benchmarkConcurrentDecisionThroughput();
        benchmarkPolicyEvaluationCost(sequentialDecisions.get(0).policyKey());
        benchmarkReplayThroughput(sequentialDecisions);
        benchmarkAuditChainVerificationOverhead(sequentialDecisions);
    }

    private List<ProtectionDecisionResult> benchmarkSequentialDecisionThroughput() {
        BenchmarkStats stats = new BenchmarkStats();
        List<ProtectionDecisionResult> results = new ArrayList<>(SEQUENTIAL_DECISIONS);
        Instant start = Instant.now();
        for (int i = 0; i < SEQUENTIAL_DECISIONS; i++) {
            long callStart = System.nanoTime();
            try {
                ProtectionDecisionResult result = protectionDecisionService.decide(decisionCommand("seq", i));
                results.add(result);
                stats.record(System.nanoTime() - callStart);
            } catch (RuntimeException ex) {
                stats.recordError();
            }
        }
        Duration wallClock = Duration.between(start, Instant.now());
        REPORT.addSection(stats.toMarkdownSection(
                "1a. Decision throughput and latency (sequential)",
                "End-to-end `ProtectionDecisionService.decide()` calls (risk scoring, policy evaluation, "
                        + "audit-chain append, challenge issuance where applicable, outbox write), one at a time, "
                        + "no concurrency.",
                wallClock));
        assertThat(stats.errors()).isZero();
        return results;
    }

    private void benchmarkConcurrentDecisionThroughput() {
        BenchmarkStats stats = new BenchmarkStats();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            List<Future<?>> futures = new ArrayList<>(CONCURRENT_DECISIONS);
            Instant start = Instant.now();
            for (int i = 0; i < CONCURRENT_DECISIONS; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    long callStart = System.nanoTime();
                    try {
                        protectionDecisionService.decide(decisionCommand("conc", index));
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
            Duration wallClock = Duration.between(start, Instant.now());
            REPORT.addSection(stats.toMarkdownSection(
                    "1b. Decision throughput and latency (concurrent, " + CONCURRENCY + " threads)",
                    "Same `decide()` call as above, submitted concurrently across " + CONCURRENCY
                            + " threads sharing the default Hikari pool (`spring.datasource.hikari.maximum-pool-size`, "
                            + "default 10) -- shows sustained throughput under realistic parallel load.",
                    wallClock));
        } finally {
            executor.shutdown();
        }
    }

    private void benchmarkPolicyEvaluationCost(String policyKey) {
        BenchmarkStats stats = new BenchmarkStats();
        Instant start = Instant.now();
        for (int i = 0; i < POLICY_EVAL_ITERATIONS; i++) {
            int riskScore = i % 101;
            long callStart = System.nanoTime();
            try {
                policyEvaluationService.evaluate(policyKey, riskScore);
                stats.record(System.nanoTime() - callStart);
            } catch (RuntimeException ex) {
                stats.recordError();
            }
        }
        Duration wallClock = Duration.between(start, Instant.now());
        REPORT.addSection(stats.toMarkdownSection(
                "2. Policy evaluation cost (isolated)",
                "`PolicyEvaluationService.evaluate(policyKey, riskScore)` called directly, bypassing risk "
                        + "scoring, audit, and outbox entirely -- isolates the cost of loading the active policy "
                        + "version and comparing thresholds.",
                wallClock));
        assertThat(stats.errors()).isZero();
    }

    private void benchmarkReplayThroughput(List<ProtectionDecisionResult> sequentialDecisions) {
        BenchmarkStats stats = new BenchmarkStats();
        int sampleSize = Math.min(REPLAY_SAMPLE, sequentialDecisions.size());
        Instant start = Instant.now();
        for (int i = 0; i < sampleSize; i++) {
            UUID protectionRequestId = sequentialDecisions.get(i).protectionRequestId();
            long callStart = System.nanoTime();
            try {
                simulationService.replay(protectionRequestId).orElseThrow();
                stats.record(System.nanoTime() - callStart);
            } catch (RuntimeException ex) {
                stats.recordError();
            }
        }
        Duration wallClock = Duration.between(start, Instant.now());
        REPORT.addSection(stats.toMarkdownSection(
                "5. Replay throughput",
                "`SimulationService.replay(protectionRequestId)` re-running the recorded risk algorithm and "
                        + "policy version for " + sampleSize + " previously-decided requests.",
                wallClock));
        assertThat(stats.errors()).isZero();
    }

    private void benchmarkAuditChainVerificationOverhead(List<ProtectionDecisionResult> sequentialDecisions) {
        UUID firstDecisionId = sequentialDecisions.get(0).decisionId();
        AuditChainRecordProof firstProof = auditChainVerificationService.findProof(firstDecisionId).orElseThrow();
        AuditChainRootHash tip = auditChainVerificationService.currentRootHash().orElseThrow();

        BenchmarkStats stats = new BenchmarkStats();
        Instant start = Instant.now();
        long callStart = System.nanoTime();
        AuditChainVerificationResult result =
                auditChainVerificationService.verifyRange(firstProof.chainSequence(), tip.chainSequence());
        stats.record(System.nanoTime() - callStart);
        if (!result.valid()) {
            stats.recordError();
        }
        Duration wallClock = Duration.between(start, Instant.now());
        REPORT.addSection(stats.toMarkdownSection(
                "8. Audit/hash-chain verification overhead",
                "`AuditChainVerificationService.verifyRange(" + firstProof.chainSequence() + ", "
                        + tip.chainSequence() + ")` recomputing and checking every content hash and chain link over "
                        + result.recordsChecked() + " decision-trace records (recordsChecked=" + result.recordsChecked()
                        + ", valid=" + result.valid() + "). This is a distinct, on-demand verification pass, not the "
                        + "per-write append cost already folded into benchmark #1 and #2's decision latency above.",
                wallClock));
        assertThat(result.valid()).isTrue();
    }

    private ProtectionDecisionCommand decisionCommand(String slug, int index) {
        RiskSignals signals = new RiskSignals(
                index % 6,
                index % 2 == 0,
                index % 7 == 0,
                index % 11 == 0,
                NetworkRiskLevel.values()[index % NetworkRiskLevel.values().length]);
        RiskSignalEnvelope envelope = new RiskSignalEnvelope(
                signals, "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true);
        return new ProtectionDecisionCommand(
                "benchmark-" + slug + "-" + UUID.randomUUID() + "@example.test",
                ProtectionEventType.LOGIN_ATTEMPT,
                envelope,
                "idem-benchmark-" + slug + "-" + UUID.randomUUID());
    }
}
