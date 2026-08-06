package io.github.viniciusssantos.accountshieldcli.scenario;

import picocli.CommandLine.Command;

@Command(
        name = "scenario",
        description = "Run and report on deterministic adversarial scenarios",
        mixinStandardHelpOptions = true,
        subcommands = {ScenarioListCommand.class, ScenarioRunCommand.class, ScenarioReportCommand.class})
public final class ScenarioCommand {
}
