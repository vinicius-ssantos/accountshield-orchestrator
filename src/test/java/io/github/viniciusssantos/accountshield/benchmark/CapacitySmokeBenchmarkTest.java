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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Issue #50's "CI runs a smoke benchmark without flaky hard thresholds" acceptance criterion: a
 * tiny, untagged slice of {@link CapacityBenchmarkTest}'s decision-throughput harness that runs in
 * the default CI gate (ci.yml, not excluded like {@code @Tag("benchmark")}). It proves the
 * benchmark harness itself works end to end on every PR; it does not assert any latency/throughput
 * number, since a single shared CI runner's numbers are too noisy to gate on -- the full
 * measurement-grade run happens nightly via {@link CapacityBenchmarkTest}. Only that the harness
 * completes without error is asserted.
 */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class CapacitySmokeBenchmarkTest {

    private static final Path REPORT_PATH = Path.of("target/benchmark-reports/capacity-smoke.md");
    private static final int ITERATIONS = 10;

    @Autowired
    private ProtectionDecisionService protectionDecisionService;

    @Test
    void decisionHarnessSmokeTest() throws Exception {
        BenchmarkStats stats = new BenchmarkStats();
        Instant start = Instant.now();
        for (int i = 0; i < ITERATIONS; i++) {
            long callStart = System.nanoTime();
            try {
                protectionDecisionService.decide(decisionCommand(i));
                stats.record(System.nanoTime() - callStart);
            } catch (RuntimeException ex) {
                stats.recordError();
            }
        }
        Duration wallClock = Duration.between(start, Instant.now());

        BenchmarkReport report = new BenchmarkReport("AccountShield Capacity Smoke Benchmark (default CI gate)");
        report.addSection(stats.toMarkdownSection(
                "Decision throughput smoke check",
                ITERATIONS + " sequential `decide()` calls -- proves the benchmark harness runs end to end on "
                        + "every PR. No latency/throughput threshold is asserted here (a single shared CI runner is "
                        + "too noisy to gate on); see the nightly `capacity-benchmark.md` artifact for "
                        + "measurement-grade numbers.",
                wallClock));
        report.writeTo(REPORT_PATH);

        assertThat(stats.errors()).isZero();
        assertThat(stats.count()).isEqualTo(ITERATIONS);
    }

    private ProtectionDecisionCommand decisionCommand(int index) {
        RiskSignalEnvelope envelope = new RiskSignalEnvelope(
                new RiskSignals(index % 4, index % 2 == 0, false, false, NetworkRiskLevel.LOW),
                "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true);
        return new ProtectionDecisionCommand(
                "benchmark-smoke-" + UUID.randomUUID() + "@example.test",
                ProtectionEventType.LOGIN_ATTEMPT,
                envelope,
                "idem-benchmark-smoke-" + UUID.randomUUID());
    }
}
