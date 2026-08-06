package io.github.viniciusssantos.accountshieldcli;

import io.github.viniciusssantos.accountshieldcli.evidence.EvidenceCommand;
import io.github.viniciusssantos.accountshieldcli.policy.PolicyCommand;
import io.github.viniciusssantos.accountshieldcli.scenario.ScenarioCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Issue #56's Scenario CLI: runs deterministic adversarial scenarios, lints/diffs policies, and
 * verifies evidence bundles -- built entirely on {@code accountshield-sdk} (issue #55), never on
 * any server-internal package. See {@code cli/README.md} for the full command reference and exit-
 * code contract, and {@code docs/adr/0038-scenario-cli.md} for the design.
 */
@Command(
        name = "accountshield-cli",
        mixinStandardHelpOptions = true,
        versionProvider = AccountShieldCli.VersionProvider.class,
        subcommands = {ScenarioCommand.class, PolicyCommand.class, EvidenceCommand.class})
public final class AccountShieldCli {

    public static void main(String[] args) {
        System.exit(new CommandLine(new AccountShieldCli()).execute(args));
    }

    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {"accountshield-cli 0.1.0-SNAPSHOT"};
        }
    }
}
