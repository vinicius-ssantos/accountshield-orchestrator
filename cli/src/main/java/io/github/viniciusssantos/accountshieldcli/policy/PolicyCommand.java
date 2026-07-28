package io.github.viniciusssantos.accountshieldcli.policy;

import picocli.CommandLine.Command;

@Command(
        name = "policy",
        description = "Lint a candidate policy threshold set, or diff a candidate version against recorded history",
        mixinStandardHelpOptions = true,
        subcommands = {PolicyLintCommand.class, PolicyDiffCommand.class})
public final class PolicyCommand {
}
