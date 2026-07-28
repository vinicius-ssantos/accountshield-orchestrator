package io.github.viniciusssantos.accountshieldcli.scenario;

import io.github.viniciusssantos.accountshieldcli.CommonOptions;
import io.github.viniciusssantos.accountshieldcli.ExitCodes;
import io.github.viniciusssantos.accountshieldcli.JsonSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "report", description = "Render a Markdown report for a previous 'scenario run'")
public final class ScenarioReportCommand implements Callable<Integer> {

    @Mixin
    private CommonOptions options;

    @Parameters(index = "0", description = "Run ID printed by 'scenario run'")
    private String runId;

    @Option(names = "--output-dir", description = "Directory run results were persisted to "
            + "(default: ~/.accountshield-cli/runs)")
    private String outputDir;

    @Option(names = "--out", description = "Write the Markdown report to this file instead of stdout")
    private String outFile;

    @Override
    public Integer call() {
        Path resultFile = ScenarioRunCommand.resolveOutputDirStatic(outputDir).resolve(runId + ".json");
        if (!Files.exists(resultFile)) {
            System.err.println("no run result found for " + runId + " at " + resultFile);
            return ExitCodes.EXECUTION_ERROR;
        }

        ScenarioRunResult result;
        try {
            result = JsonSupport.MAPPER.readValue(Files.readString(resultFile), ScenarioRunResult.class);
        } catch (IOException exception) {
            System.err.println("failed to read " + resultFile + ": " + exception.getMessage());
            return ExitCodes.EXECUTION_ERROR;
        }

        if (options.json()) {
            System.out.println(JsonSupport.toPrettyJson(result));
            return ExitCodes.SUCCESS;
        }

        String markdown = renderMarkdown(result);
        if (outFile != null && !outFile.isBlank()) {
            try {
                Files.writeString(Path.of(outFile), markdown);
            } catch (IOException exception) {
                System.err.println("failed to write " + outFile + ": " + exception.getMessage());
                return ExitCodes.EXECUTION_ERROR;
            }
        } else {
            System.out.println(markdown);
        }
        return ExitCodes.SUCCESS;
    }

    private String renderMarkdown(ScenarioRunResult result) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Scenario run report: ").append(result.scenarioName())
                .append(result.matched() ? " ✅" : " ❌ DIVERGED").append("\n\n");
        markdown.append("- **Run ID:** ").append(result.runId()).append('\n');
        markdown.append("- **Executed at:** ").append(result.executedAt()).append('\n');
        markdown.append("- **Correlation ID:** ").append(result.correlationId()).append('\n');
        markdown.append("- **Decision ID:** ").append(result.decisionId()).append('\n');
        markdown.append("- **Protection request ID:** ").append(result.protectionRequestId()).append('\n');
        markdown.append("- **Policy:** ").append(result.policyKey()).append(':').append(result.policyVersion()).append('\n');
        markdown.append("- **Algorithm version:** ").append(result.algorithmVersion()).append('\n');
        markdown.append("- **Score:** ").append(result.actualScore())
                .append(" (expected ").append(result.expectedScore()).append(")\n");
        markdown.append("- **Outcome:** ").append(result.actualOutcome())
                .append(" (expected ").append(result.expectedOutcome()).append(")\n");
        markdown.append("- **Reason codes:** ").append(result.reasonCodes()).append('\n');
        if (result.followUpNote() != null) {
            markdown.append("- **Follow-up:** ").append(result.followUpNote()).append('\n');
        }
        return markdown.toString();
    }
}
