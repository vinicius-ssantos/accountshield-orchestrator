package io.github.viniciusssantos.accountshield.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.LocalJwtKeys;
import io.github.viniciusssantos.accountshield.PostgreSqlTestConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issue #56's "CLI can execute at least the initial adversarial scenarios" acceptance criterion,
 * proven the same honest way {@code SdkContractVerificationTest} proves the SDK: this test does
 * not call any CLI class directly (there is no reactor connecting this project to {@code cli/}) --
 * it runs the real, already-assembled {@code cli/target/accountshield-cli.jar} as a real
 * subprocess (see ci.yml: the cli module is built before this build's own verify step) against
 * this test's own live, random-port server instance, and asserts on the real process exit code and
 * real stdout JSON.
 *
 * <p>The CLI jar is a build artifact of a standalone Maven project outside this reactor, so a
 * clean clone that runs {@code ./mvnw verify} without first building the CLI would otherwise hit
 * a hard failure here. {@code assumeTrue} turns that into a skip, exercising the real subprocess
 * end to end whenever the jar is present -- which CI guarantees by building it first. This only
 * covers the CLI half of issue #28's self-sufficiency goal: the root build's test scope also has
 * a hard compile-time dependency on {@code accountshield-sdk} (see {@code
 * SdkContractVerificationTest}), which still must be installed locally before {@code ./mvnw
 * verify} can even reach {@code test-compile} (issue #148 / F-04, see README's developer
 * workflow section).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgreSqlTestConfiguration.class)
class CliEndToEndTest {

    private static final Path CLI_JAR = Path.of("cli", "target", "accountshield-cli.jar");

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private LocalJwtKeys localJwtKeys;

    @Test
    void scenarioRunExecutesARealAdversarialScenarioAgainstALiveServer(@TempDir Path outputDir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(CLI_JAR),
                () -> CLI_JAR + " is not built -- run 'mvn package' in cli/ (after 'mvn install' in sdk/) "
                        + "to exercise the CLI subprocess end to end; skipped otherwise so a clean-clone "
                        + "./mvnw verify stays self-sufficient.");

        String token = localJwtKeys.signToken(
                "cli-e2e-test-client", List.of("PROTECTION_CLIENT"), Duration.ofMinutes(5), Clock.systemUTC());

        Process process = new ProcessBuilder(
                "java", "-jar", CLI_JAR.toAbsolutePath().toString(),
                "scenario", "run", "credential-stuffing",
                "--base-url", "http://localhost:" + port,
                "--token", token,
                "--output-dir", outputDir.toString(),
                "--json")
                .redirectErrorStream(true)
                .start();

        String stdout = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        assertThat(finished).as("CLI process must finish within 30s, stdout so far: %s", stdout).isTrue();
        assertThat(process.exitValue())
                .as("expected exit code 0 (scenario matched its ADR 0034 expected outcome); stdout: %s", stdout)
                .isZero();

        var result = JsonMapper.builder().build().readTree(stdout);
        assertThat(result.get("scenarioName").asString()).isEqualTo("credential-stuffing");
        assertThat(result.get("actualScore").asInt()).isEqualTo(95);
        assertThat(result.get("actualOutcome").asString()).isEqualTo("TEMPORARILY_BLOCK");
        assertThat(result.get("matched").asBoolean()).isTrue();

        Path persisted = outputDir.resolve(result.get("runId").asString() + ".json");
        assertThat(Files.exists(persisted)).as("scenario run must persist a run result for 'scenario report'").isTrue();
    }
}
