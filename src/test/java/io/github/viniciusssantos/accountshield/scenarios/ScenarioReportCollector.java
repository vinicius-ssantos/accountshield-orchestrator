package io.github.viniciusssantos.accountshield.scenarios;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects {@link ScenarioReport}s across every scenario test method sharing this Spring context
 * (issue #54: "results can feed demo dashboards") and writes them as one Markdown report, uploaded
 * as a CI artifact -- matching this codebase's existing "generated report -> build artifact"
 * convention (ADR 0031's coverage/SBOM reports, ADR 0029's contract artifacts).
 */
public final class ScenarioReportCollector {

    private static final List<ScenarioReport> REPORTS = Collections.synchronizedList(new ArrayList<>());

    private ScenarioReportCollector() {
    }

    public static void record(ScenarioReport report) {
        REPORTS.add(report);
    }

    public static void writeMarkdownReport(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        StringBuilder markdown = new StringBuilder("# Adversarial Account-Takeover Scenario Lab Report\n\n");
        markdown.append("Generated: ").append(Instant.now()).append("\n\n");
        markdown.append("All account references and identifiers below are synthetic test fixtures; ")
                .append("no real personal data is used anywhere in this suite.\n\n");
        synchronized (REPORTS) {
            if (REPORTS.isEmpty()) {
                markdown.append("_No scenarios ran in this session._\n");
            }
            for (ScenarioReport report : REPORTS) {
                markdown.append(report.toMarkdown());
            }
        }
        Files.writeString(path, markdown.toString(), StandardCharsets.UTF_8);
    }
}
