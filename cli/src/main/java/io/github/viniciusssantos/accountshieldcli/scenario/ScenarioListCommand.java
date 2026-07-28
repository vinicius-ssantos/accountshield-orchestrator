package io.github.viniciusssantos.accountshieldcli.scenario;

import io.github.viniciusssantos.accountshieldcli.ExitCodes;
import io.github.viniciusssantos.accountshieldcli.JsonSupport;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "list", description = "List the available scenario names")
public final class ScenarioListCommand implements Callable<Integer> {

    @Mixin
    private io.github.viniciusssantos.accountshieldcli.CommonOptions options;

    @Override
    public Integer call() {
        if (options.json()) {
            System.out.println(JsonSupport.toPrettyJson(ScenarioDefinition.ALL));
        } else {
            for (ScenarioDefinition scenario : ScenarioDefinition.ALL) {
                System.out.printf(
                        "%-20s %-16s %-14s %s%n",
                        scenario.name(), scenario.expectedOutcome(), "score=" + scenario.expectedScore(),
                        scenario.description());
            }
        }
        return ExitCodes.SUCCESS;
    }
}
