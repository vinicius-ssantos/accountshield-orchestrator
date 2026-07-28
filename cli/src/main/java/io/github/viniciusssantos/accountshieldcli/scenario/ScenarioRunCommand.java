package io.github.viniciusssantos.accountshieldcli.scenario;

import io.github.viniciusssantos.accountshieldcli.CommonOptions;
import io.github.viniciusssantos.accountshieldcli.ExitCodes;
import io.github.viniciusssantos.accountshieldcli.JsonSupport;
import io.github.viniciusssantos.accountshieldsdk.AccountShieldClient;
import io.github.viniciusssantos.accountshieldsdk.model.ChallengePurpose;
import io.github.viniciusssantos.accountshieldsdk.model.ChallengeVerificationRequest;
import io.github.viniciusssantos.accountshieldsdk.model.ChallengeVerificationResponse;
import io.github.viniciusssantos.accountshieldsdk.model.ProtectionDecisionRequest;
import io.github.viniciusssantos.accountshieldsdk.model.ProtectionDecisionResponse;
import io.github.viniciusssantos.accountshieldsdk.model.ProtectionOutcome;
import io.github.viniciusssantos.accountshieldsdk.model.RecoveryResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "run", description = "Run one named scenario against a live AccountShield instance")
public final class ScenarioRunCommand implements Callable<Integer> {

    @Mixin
    private CommonOptions options;

    @Parameters(index = "0", description = "Scenario name (see 'scenario list')")
    private String name;

    @Option(names = "--output-dir", description = "Directory run results are persisted to "
            + "(default: ~/.accountshield-cli/runs)")
    private String outputDir;

    @Override
    public Integer call() {
        ScenarioDefinition scenario;
        try {
            scenario = ScenarioDefinition.byName(name);
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            return ExitCodes.EXECUTION_ERROR;
        }

        AccountShieldClient client = options.buildClient();
        String correlationId = options.correlationId();
        UUID runId = UUID.randomUUID();
        String idempotencyKey = "cli-" + scenario.name() + "-" + runId;

        ProtectionDecisionResponse response;
        try {
            response = client.decideProtection(
                    ProtectionDecisionRequest.builder(syntheticAccountReference(scenario.name()), scenario.eventType())
                            .failedAttempts(scenario.failedAttempts())
                            .newDevice(scenario.newDevice())
                            .impossibleTravel(scenario.impossibleTravel())
                            .compromisedCredential(scenario.compromisedCredential())
                            .networkRiskLevel(scenario.networkRiskLevel())
                            .signalConfidence(scenario.signalConfidence())
                            .idempotencyKey(idempotencyKey)
                            .build(),
                    correlationId);
        } catch (RuntimeException exception) {
            System.err.println("scenario run failed: " + exception.getMessage());
            return ExitCodes.EXECUTION_ERROR;
        }

        String followUpNote = runFollowUp(client, scenario, response);

        List<String> reasonCodes = response.reasons().stream().map(ProtectionDecisionResponse.Reason::code).toList();
        boolean matched = response.outcome() == scenario.expectedOutcome() && response.riskScore() == scenario.expectedScore();

        ScenarioRunResult result = new ScenarioRunResult(
                runId, scenario.name(), Instant.now(), correlationId,
                response.decisionId(), response.protectionRequestId(), response.policyKey(), response.policyVersion(),
                response.algorithmVersion(), response.riskScore(), scenario.expectedScore(),
                response.outcome().name(), scenario.expectedOutcome().name(), reasonCodes, matched, followUpNote);

        persist(result);
        print(result);

        return matched ? ExitCodes.SUCCESS : ExitCodes.CHECK_FAILED;
    }

    private String runFollowUp(AccountShieldClient client, ScenarioDefinition scenario, ProtectionDecisionResponse response) {
        if (response.outcome() == ProtectionOutcome.REQUIRE_STEP_UP && response.challenge() != null) {
            ChallengeVerificationResponse verification = client.verifyChallenge(
                    response.challenge().challengeId(),
                    new ChallengeVerificationRequest("000000", ChallengePurpose.PROTECTION_STEP_UP, response.protectionRequestId()),
                    options.correlationId());
            return "Challenge " + response.challenge().challengeId() + " -> status=" + verification.status()
                    + " remainingAttempts=" + verification.remainingAttempts();
        }
        if (response.outcome() == ProtectionOutcome.START_RECOVERY && response.recoveryAuthorizationId() != null) {
            RecoveryResponse recovery = client.initiateRecovery(response.recoveryAuthorizationId(), options.correlationId());
            return "Recovery " + recovery.recoveryId() + " -> status=" + recovery.status();
        }
        return null;
    }

    private void persist(ScenarioRunResult result) {
        try {
            Path dir = resolveOutputDir();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(result.runId() + ".json"), JsonSupport.toPrettyJson(result));
        } catch (IOException exception) {
            System.err.println("warning: failed to persist run result: " + exception.getMessage());
        }
    }

    private void print(ScenarioRunResult result) {
        if (options.json()) {
            System.out.println(JsonSupport.toPrettyJson(result));
            return;
        }
        System.out.println("Run ID:            " + result.runId());
        System.out.println("Scenario:           " + result.scenarioName());
        System.out.println("Correlation ID:     " + result.correlationId());
        System.out.println("Decision ID:        " + result.decisionId());
        System.out.println("Protection request: " + result.protectionRequestId());
        System.out.println("Policy:             " + result.policyKey() + ":" + result.policyVersion());
        System.out.println("Algorithm:          " + result.algorithmVersion());
        System.out.println("Score:              " + result.actualScore() + " (expected " + result.expectedScore() + ")");
        System.out.println("Outcome:            " + result.actualOutcome() + " (expected " + result.expectedOutcome() + ")");
        System.out.println("Reasons:            " + result.reasonCodes());
        System.out.println("Matched:            " + result.matched());
        if (result.followUpNote() != null) {
            System.out.println("Follow-up:          " + result.followUpNote());
        }
    }

    static Path resolveOutputDirStatic(String outputDir) {
        if (outputDir != null && !outputDir.isBlank()) {
            return Path.of(outputDir);
        }
        return Path.of(System.getProperty("user.home"), ".accountshield-cli", "runs");
    }

    private Path resolveOutputDir() {
        return resolveOutputDirStatic(outputDir);
    }

    private String syntheticAccountReference(String scenarioName) {
        return "cli-" + scenarioName + "-" + UUID.randomUUID() + "@example.test";
    }
}
