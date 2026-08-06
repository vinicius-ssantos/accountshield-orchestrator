package io.github.viniciusssantos.accountshieldcli;

/**
 * Stable, documented exit-code contract (issue #56: "failures return non-zero exit codes",
 * "JSON output is stable and documented" -- documented in {@code cli/README.md}):
 * 0 = success and the checked condition held (scenario matched its expected outcome, no lint
 * errors, impact within threshold, bundle valid); 1 = execution error (network/HTTP/parse
 * failure, unknown scenario name, missing file); 2 = the command ran successfully but the
 * checked business condition failed (scenario diverged, lint found an ERROR diagnostic, impact
 * exceeded its divergence threshold, evidence bundle is invalid).
 */
public final class ExitCodes {

    public static final int SUCCESS = 0;
    public static final int EXECUTION_ERROR = 1;
    public static final int CHECK_FAILED = 2;

    private ExitCodes() {
    }
}
