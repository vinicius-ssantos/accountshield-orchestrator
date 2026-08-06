package io.github.viniciusssantos.accountshield.benchmark;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collects per-call latency samples (nanoseconds) and error counts for one benchmark dimension,
 * and renders them as p50/p95/p99, mean, min/max, throughput, and error rate -- the measurements
 * issue #50 requires every capacity claim to carry (roadmap.md Gate 7: "capacity claims include
 * environment and p50/p95/p99 evidence").
 */
public final class BenchmarkStats {

    private final List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger errorCount = new AtomicInteger();

    public void record(long nanos) {
        latenciesNanos.add(nanos);
    }

    public void recordError() {
        errorCount.incrementAndGet();
    }

    public int count() {
        return latenciesNanos.size();
    }

    public int errors() {
        return errorCount.get();
    }

    private double percentileMillis(double percentile) {
        List<Long> sorted;
        synchronized (latenciesNanos) {
            sorted = new ArrayList<>(latenciesNanos);
        }
        if (sorted.isEmpty()) {
            return 0.0;
        }
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index) / 1_000_000.0;
    }

    public double p50Millis() {
        return percentileMillis(0.50);
    }

    public double p95Millis() {
        return percentileMillis(0.95);
    }

    public double p99Millis() {
        return percentileMillis(0.99);
    }

    public double meanMillis() {
        synchronized (latenciesNanos) {
            return latenciesNanos.stream().mapToLong(Long::longValue).average().orElse(0.0) / 1_000_000.0;
        }
    }

    public double minMillis() {
        synchronized (latenciesNanos) {
            return latenciesNanos.stream().mapToLong(Long::longValue).min().orElse(0) / 1_000_000.0;
        }
    }

    public double maxMillis() {
        synchronized (latenciesNanos) {
            return latenciesNanos.stream().mapToLong(Long::longValue).max().orElse(0) / 1_000_000.0;
        }
    }

    public double errorRate() {
        int total = count() + errors();
        return total == 0 ? 0.0 : (double) errors() / total;
    }

    public double throughputOpsPerSecond(Duration wallClock) {
        double seconds = wallClock.toNanos() / 1_000_000_000.0;
        return seconds <= 0 ? 0.0 : count() / seconds;
    }

    /**
     * Renders this dimension's numbers as one Markdown subsection, including the wall-clock
     * throughput for the measured operation and an explicit error rate -- never asserted against a
     * hardcoded threshold, since no prior baseline exists for this codebase (matching the same
     * "report, don't gate" stance ADR 0031 took for JaCoCo coverage).
     */
    public String toMarkdownSection(String title, String description, Duration wallClock) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(title).append("\n\n");
        sb.append(description).append("\n\n");
        sb.append("| Metric | Value |\n|---|---|\n");
        sb.append("| Samples | ").append(count()).append(" |\n");
        sb.append(String.format(Locale.ROOT, "| p50 | %.2f ms |%n", p50Millis()));
        sb.append(String.format(Locale.ROOT, "| p95 | %.2f ms |%n", p95Millis()));
        sb.append(String.format(Locale.ROOT, "| p99 | %.2f ms |%n", p99Millis()));
        sb.append(String.format(Locale.ROOT, "| Mean | %.2f ms |%n", meanMillis()));
        sb.append(String.format(Locale.ROOT, "| Min / Max | %.2f ms / %.2f ms |%n", minMillis(), maxMillis()));
        sb.append(String.format(Locale.ROOT, "| Throughput | %.1f ops/sec |%n", throughputOpsPerSecond(wallClock)));
        sb.append(String.format(Locale.ROOT, "| Error rate | %.2f%% (%d errors) |%n", errorRate() * 100, errors()));
        sb.append('\n');
        return sb.toString();
    }
}
