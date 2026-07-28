package io.github.viniciusssantos.accountshieldcli.evidence;

import picocli.CommandLine.Command;

@Command(
        name = "evidence",
        description = "Verify a previously-exported evidence bundle",
        mixinStandardHelpOptions = true,
        subcommands = {EvidenceVerifyCommand.class})
public final class EvidenceCommand {
}
