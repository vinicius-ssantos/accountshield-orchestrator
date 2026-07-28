package io.github.viniciusssantos.accountshieldcli.policy;

import io.github.viniciusssantos.accountshieldcli.CommonOptions;
import io.github.viniciusssantos.accountshieldcli.ExitCodes;
import io.github.viniciusssantos.accountshieldcli.JsonSupport;
import io.github.viniciusssantos.accountshieldsdk.model.PolicyAnalysisRequest;
import io.github.viniciusssantos.accountshieldsdk.model.PolicyAnalysisResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

/**
 * A local JSON file with {@code allowMaxScore}/{@code stepUpMaxScore}/{@code recoveryMaxScore}
 * (any may be null/omitted to trigger a {@code *_MISSING} diagnostic) -- exactly the same shape
 * {@code POST /api/v1/policies/analyze} accepts, so the file deserializes straight into the
 * request with no CLI-specific translation.
 */
@Command(name = "lint", description = "Statically analyze a candidate policy threshold file, without creating it")
public final class PolicyLintCommand implements Callable<Integer> {

    @Mixin
    private CommonOptions options;

    @Parameters(index = "0", description = "Path to a JSON file with allowMaxScore/stepUpMaxScore/recoveryMaxScore")
    private String file;

    @Override
    public Integer call() {
        PolicyAnalysisRequest request;
        try {
            request = JsonSupport.MAPPER.readValue(Files.readString(Path.of(file)), PolicyAnalysisRequest.class);
        } catch (IOException exception) {
            System.err.println("failed to read " + file + ": " + exception.getMessage());
            return ExitCodes.EXECUTION_ERROR;
        }

        PolicyAnalysisResult result;
        try {
            result = options.buildClient().analyzePolicy(request, options.correlationId());
        } catch (RuntimeException exception) {
            System.err.println("policy lint failed: " + exception.getMessage());
            return ExitCodes.EXECUTION_ERROR;
        }

        if (options.json()) {
            System.out.println(JsonSupport.toPrettyJson(result));
        } else {
            System.out.println("Analyzer version: " + result.analyzerVersion());
            for (PolicyAnalysisResult.PolicyDiagnostic diagnostic : result.diagnostics()) {
                System.out.printf("[%s] %s (%s): %s%n",
                        diagnostic.severity(), diagnostic.code(), diagnostic.path(), diagnostic.message());
            }
            if (result.diagnostics().isEmpty()) {
                System.out.println("No diagnostics.");
            }
        }

        return result.hasErrors() ? ExitCodes.CHECK_FAILED : ExitCodes.SUCCESS;
    }
}
