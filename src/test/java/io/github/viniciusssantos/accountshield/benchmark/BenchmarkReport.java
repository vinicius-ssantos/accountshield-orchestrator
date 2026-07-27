package io.github.viniciusssantos.accountshield.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggregates one benchmark run's sections into a single Markdown report, uploaded as a CI/nightly
 * artifact -- the same "generated report -> build artifact" convention used for coverage/SBOM
 * (ADR 0031), contracts (ADR 0029), and the scenario lab (ADR 0034). Each test class owns its own
 * instance (not a shared static singleton) so unrelated benchmark classes never contaminate each
 * other's report.
 */
public final class BenchmarkReport {

    private final String title;
    private final List<String> sections = Collections.synchronizedList(new ArrayList<>());

    public BenchmarkReport(String title) {
        this.title = title;
    }

    public void addSection(String markdown) {
        sections.add(markdown);
    }

    public void writeTo(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        StringBuilder markdown = new StringBuilder("# ").append(title).append("\n\n");
        markdown.append("Generated: ").append(Instant.now()).append("\n\n");
        markdown.append(environmentSection());
        synchronized (sections) {
            if (sections.isEmpty()) {
                markdown.append("_No benchmarks ran in this session._\n");
            }
            sections.forEach(markdown::append);
        }
        Files.writeString(path, markdown.toString(), StandardCharsets.UTF_8);
    }

    private String environmentSection() {
        Runtime runtime = Runtime.getRuntime();
        return "## Environment\n\n"
                + "This run's exact environment -- required because these numbers are meaningless without it:\n\n"
                + "| Property | Value |\n|---|---|\n"
                + "| Java | " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ") |\n"
                + "| OS | " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ") |\n"
                + "| Available processors | " + runtime.availableProcessors() + " |\n"
                + "| Max JVM heap | " + (runtime.maxMemory() / (1024 * 1024)) + " MB |\n"
                + "| Database | PostgreSQL (Testcontainers, see PostgreSqlTestConfiguration for the exact image tag) |\n\n"
                + "These numbers are a single wall-clock run on this CI/nightly runner's shared hardware, not an"
                + " isolated, repeated-trial statistical benchmark -- treat them as directional evidence of shape and"
                + " relative cost, not as SLA-grade absolute numbers. Reproduce locally with:"
                + " `./mvnw -Dgroups=benchmark test -Dtest=CapacityBenchmarkTest,ConnectionPoolSaturationTest`"
                + " against a real Postgres instance for hardware-specific numbers.\n\n";
    }
}
