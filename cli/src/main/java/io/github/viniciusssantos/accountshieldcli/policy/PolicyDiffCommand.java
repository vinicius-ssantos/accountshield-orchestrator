package io.github.viniciusssantos.accountshieldcli.policy;

import io.github.viniciusssantos.accountshieldcli.CommonOptions;
import io.github.viniciusssantos.accountshieldcli.ExitCodes;
import io.github.viniciusssantos.accountshieldcli.JsonSupport;
import io.github.viniciusssantos.accountshieldsdk.model.PolicyImpactReport;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Named {@code policy diff <stable> <candidate>} per issue #56, but the real
 * {@code /api/v1/simulation/policy-impact} endpoint this wraps has no explicit "stable version"
 * parameter -- it replays recent historical decision traces for a policy key and compares each
 * one's *actually-recorded* outcome (whatever policy version really produced it) against what
 * {@code candidateVersion} would produce. So the first argument here is the policy key the history
 * belongs to, not a second literal version to diff against; see ADR 0038 for why this command was
 * adapted to the real API shape rather than the issue's literal wording.
 */
@Command(name = "diff", description = "Compare a candidate policy version against recent recorded history")
public final class PolicyDiffCommand implements Callable<Integer> {

    @Mixin
    private CommonOptions options;

    @Parameters(index = "0", description = "Policy key whose recent historical decisions to replay")
    private String policyKey;

    @Parameters(index = "1", description = "Candidate policy version to evaluate those historical decisions against")
    private String candidateVersion;

    @Option(names = "--max-samples", description = "Maximum historical decisions to sample (default: 5000)")
    private int maxSamples = 5000;

    @Override
    public Integer call() {
        PolicyImpactReport report;
        try {
            report = options.buildClient().analyzePolicyImpact(policyKey, candidateVersion, maxSamples, options.correlationId());
        } catch (RuntimeException exception) {
            System.err.println("policy diff failed: " + exception.getMessage());
            return ExitCodes.EXECUTION_ERROR;
        }

        if (options.json()) {
            System.out.println(JsonSupport.toPrettyJson(report));
        } else {
            System.out.println("Policy key:              " + report.policyKey());
            System.out.println("Candidate version:        " + report.candidatePolicyVersion());
            System.out.println("Original versions seen:   " + report.originalPolicyVersionsObserved());
            System.out.println("Total decisions sampled:  " + report.totalDecisions());
            System.out.println("Divergent decisions:      " + report.divergentDecisionsCount()
                    + " (" + String.format(java.util.Locale.ROOT, "%.2f", report.divergencePercentage()) + "%)");
            System.out.println("Divergence threshold:     " + report.maxDivergencePercentageThreshold() + "%");
            System.out.println("Exceeds threshold:        " + report.exceedsDivergenceThreshold());
            System.out.println("Transition matrix:        " + report.transitionMatrix());
            if (!report.divergentDecisions().isEmpty()) {
                System.out.println("Divergent decisions (redacted):");
                report.divergentDecisions().forEach(decision -> System.out.println("  " + decision));
            }
        }

        return report.exceedsDivergenceThreshold() ? ExitCodes.CHECK_FAILED : ExitCodes.SUCCESS;
    }
}
