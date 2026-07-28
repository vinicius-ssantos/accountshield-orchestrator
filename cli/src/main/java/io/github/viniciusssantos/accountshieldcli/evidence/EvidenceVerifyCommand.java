package io.github.viniciusssantos.accountshieldcli.evidence;

import io.github.viniciusssantos.accountshieldcli.CommonOptions;
import io.github.viniciusssantos.accountshieldcli.ExitCodes;
import io.github.viniciusssantos.accountshieldcli.JsonSupport;
import io.github.viniciusssantos.accountshieldsdk.model.EvidenceVerificationResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

/**
 * Sends the bundle file's raw JSON bytes to {@code POST /api/v1/evidence/verify} unmodified --
 * this command never parses or reconstructs the bundle's internal structure (see
 * {@code AccountShieldClient.verifyEvidenceBundle}'s javadoc for why: the server is the one
 * source of truth for what a valid bundle looks like, and byte-for-byte pass-through avoids any
 * risk of a subtly-wrong local reserialization silently changing the bytes being verified).
 */
@Command(name = "verify", description = "Verify a previously-exported evidence bundle file")
public final class EvidenceVerifyCommand implements Callable<Integer> {

    @Mixin
    private CommonOptions options;

    @Parameters(index = "0", description = "Path to a previously-exported evidence bundle JSON file")
    private String bundleFile;

    @Override
    public Integer call() {
        String rawBundleJson;
        try {
            rawBundleJson = Files.readString(Path.of(bundleFile));
        } catch (IOException exception) {
            System.err.println("failed to read " + bundleFile + ": " + exception.getMessage());
            return ExitCodes.EXECUTION_ERROR;
        }

        EvidenceVerificationResult result;
        try {
            result = options.buildClient().verifyEvidenceBundle(rawBundleJson, options.correlationId());
        } catch (RuntimeException exception) {
            System.err.println("evidence verify failed: " + exception.getMessage());
            return ExitCodes.EXECUTION_ERROR;
        }

        if (options.json()) {
            System.out.println(JsonSupport.toPrettyJson(result));
        } else {
            System.out.println("Valid: " + result.valid());
            if (!result.problems().isEmpty()) {
                System.out.println("Problems:");
                result.problems().forEach(problem -> System.out.println("  - " + problem));
            }
        }

        return result.valid() ? ExitCodes.SUCCESS : ExitCodes.CHECK_FAILED;
    }
}
