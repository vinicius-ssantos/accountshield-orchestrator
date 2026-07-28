package io.github.viniciusssantos.accountshieldcli.scenario;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The persisted, replayable outcome of one {@code scenario run} -- issue #56's "output includes
 * decision and event provenance" plus what {@code scenario report <run-id>} reads back to render
 * a Markdown report. Written as-is to {@code <output-dir>/<runId>.json} (stable field set, no
 * server-internal types), and this exact JSON is also what {@code --json} prints to stdout.
 */
public record ScenarioRunResult(
        UUID runId,
        String scenarioName,
        Instant executedAt,
        String correlationId,
        UUID decisionId,
        UUID protectionRequestId,
        String policyKey,
        String policyVersion,
        String algorithmVersion,
        int actualScore,
        int expectedScore,
        String actualOutcome,
        String expectedOutcome,
        List<String> reasonCodes,
        boolean matched,
        String followUpNote) {
}
