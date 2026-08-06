package io.github.viniciusssantos.accountshield.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.PostgreSqlTestConfiguration;
import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeVerificationCommand;
import io.github.viniciusssantos.accountshield.evidence.EvidenceBundle;
import io.github.viniciusssantos.accountshield.evidence.EvidenceBundleService;
import io.github.viniciusssantos.accountshield.evidence.EvidenceExportCommand;
import io.github.viniciusssantos.accountshield.evidence.EvidenceVerificationResult;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskReason;
import io.github.viniciusssantos.accountshield.risk.RiskSignalEnvelope;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import io.github.viniciusssantos.accountshield.risk.SignalConfidence;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Issue #54: reproducible adversarial account-takeover scenarios, each running the real decision
 * pipeline (no mocks) with synthetic, deterministic inputs and no real personal data. Every
 * expected score/reason/outcome below is computed directly from
 * {@code DeterministicRiskAssessmentService}'s real scoring formula and the real, currently active
 * {@code account-protection-default} policy thresholds (v1.1.0: allowMaxScore=29,
 * stepUpMaxScore=69, recoveryMaxScore=89) -- not guessed. See ADR 0034 for the 5 scenarios chosen
 * here and why the other 4 issue #54 names (SIM swap, session replay, insider misuse, password
 * spraying) are explicitly deferred: this system's current signal model has no field for any of
 * them.
 */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class AccountTakeoverScenarioLabTest {

    private static final Path REPORT_PATH = Path.of("target/scenario-reports/account-takeover-scenarios.md");

    @Autowired
    private ProtectionDecisionService protectionDecisionService;

    @Autowired
    private ChallengeService challengeService;

    @Autowired
    private EvidenceBundleService evidenceBundleService;

    @AfterAll
    static void writeReport() throws Exception {
        ScenarioReportCollector.writeMarkdownReport(REPORT_PATH);
    }

    @Test
    void credentialStuffing_compromisedCredentialWithHighFailedAttemptsBlocksTheRequest() {
        RiskSignalEnvelope envelope = envelope(
                new RiskSignals(10, true, false, true, NetworkRiskLevel.MEDIUM));

        ProtectionDecisionResult result = decide("credential-stuffing", envelope, ProtectionEventType.LOGIN_ATTEMPT);

        assertThat(result.riskScore()).isEqualTo(95);
        assertThat(reasonCodes(result)).containsExactlyInAnyOrder(
                "COMPROMISED_CREDENTIAL", "FAILED_ATTEMPTS", "NETWORK_RISK_MEDIUM", "NEW_DEVICE");
        assertThat(result.outcome()).isEqualTo(ProtectionOutcome.TEMPORARILY_BLOCK);

        report("Credential stuffing", "10 failed attempts, compromised credential, new device, medium-risk network",
                95, result, List.of("COMPROMISED_CREDENTIAL", "FAILED_ATTEMPTS", "NETWORK_RISK_MEDIUM", "NEW_DEVICE"),
                "TEMPORARILY_BLOCK", "Request blocked before any challenge is issued.");
        exportAndVerifyEvidence(result, "credential-stuffing scenario");
    }

    @Test
    void impossibleTravel_flaggedSignalWithNewDeviceRequiresStepUp() {
        RiskSignalEnvelope envelope = envelope(
                new RiskSignals(0, true, true, false, NetworkRiskLevel.MEDIUM));

        ProtectionDecisionResult result = decide("impossible-travel", envelope, ProtectionEventType.LOGIN_ATTEMPT);

        assertThat(result.riskScore()).isEqualTo(60);
        assertThat(reasonCodes(result)).containsExactlyInAnyOrder(
                "IMPOSSIBLE_TRAVEL", "NETWORK_RISK_MEDIUM", "NEW_DEVICE");
        assertThat(result.outcome()).isEqualTo(ProtectionOutcome.REQUIRE_STEP_UP);
        assertThat(result.challenge()).isNotNull();

        report("Impossible travel", "impossible travel signal, new device, medium-risk network",
                60, result, List.of("IMPOSSIBLE_TRAVEL", "NETWORK_RISK_MEDIUM", "NEW_DEVICE"),
                "REQUIRE_STEP_UP", "Step-up challenge " + result.challenge().challengeId() + " issued.");
        exportAndVerifyEvidence(result, "impossible-travel scenario");
    }

    @Test
    void deviceTakeover_unfamiliarDeviceWithLowSignalConfidenceRequiresStepUp() {
        RiskSignalEnvelope envelope = new RiskSignalEnvelope(
                new RiskSignals(3, true, false, false, NetworkRiskLevel.MEDIUM),
                "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.LOW, null, true);

        ProtectionDecisionResult result = decide("device-takeover", envelope, ProtectionEventType.LOGIN_ATTEMPT);

        assertThat(result.riskScore()).isEqualTo(44);
        assertThat(reasonCodes(result)).containsExactlyInAnyOrder(
                "FAILED_ATTEMPTS", "NETWORK_RISK_MEDIUM", "NEW_DEVICE", "LOW_CONFIDENCE_SIGNAL");
        assertThat(result.outcome()).isEqualTo(ProtectionOutcome.REQUIRE_STEP_UP);

        report("Device takeover", "unfamiliar device, 3 failed attempts, medium-risk network, low-confidence signal",
                44, result,
                List.of("FAILED_ATTEMPTS", "NETWORK_RISK_MEDIUM", "NEW_DEVICE", "LOW_CONFIDENCE_SIGNAL"),
                "REQUIRE_STEP_UP", null);
        exportAndVerifyEvidence(result, "device-takeover scenario");
    }

    @Test
    void mfaFatigue_repeatedWrongCodesExhaustAttemptsAndFailTheChallenge() {
        RiskSignalEnvelope envelope = envelope(
                new RiskSignals(5, true, false, false, NetworkRiskLevel.HIGH));

        ProtectionDecisionResult result = decide("mfa-fatigue", envelope, ProtectionEventType.LOGIN_ATTEMPT);

        assertThat(result.riskScore()).isEqualTo(50);
        assertThat(reasonCodes(result)).containsExactlyInAnyOrder(
                "FAILED_ATTEMPTS", "NETWORK_RISK_HIGH", "NEW_DEVICE");
        assertThat(result.outcome()).isEqualTo(ProtectionOutcome.REQUIRE_STEP_UP);
        assertThat(result.challenge()).isNotNull();
        UUID challengeId = result.challenge().challengeId();

        // MFA fatigue: the attacker (or a fatigued legitimate user) submits wrong codes until the
        // challenge's own attempt budget (3, ChallengeApplicationService.DEFAULT_MAX_ATTEMPTS) is
        // exhausted. The first (budget - 1) wrong attempts stay CHALLENGED; the final one is the
        // call that actually crosses into FAILED and must still return normally, not throw --
        // only a call made *after* the challenge is already FAILED throws.
        for (int attempt = 0; attempt < 2; attempt++) {
            challengeService.verify(new ChallengeVerificationCommand(
                    challengeId, "wrong-code-" + attempt, ChallengePurpose.PROTECTION_STEP_UP,
                    result.protectionRequestId()));
        }

        var finalChallenge = challengeService.verify(new ChallengeVerificationCommand(
                challengeId, "wrong-code-final", ChallengePurpose.PROTECTION_STEP_UP, result.protectionRequestId()));

        report("MFA fatigue", "5 failed attempts, new device, high-risk network, then repeated wrong MFA codes",
                50, result, List.of("FAILED_ATTEMPTS", "NETWORK_RISK_HIGH", "NEW_DEVICE"),
                "REQUIRE_STEP_UP",
                "Challenge " + challengeId + " exhausted its attempt budget and transitioned to "
                        + finalChallenge.status() + ".");
        assertThat(finalChallenge.status()).isEqualTo(ChallengeStatus.FAILED);
        exportAndVerifyEvidence(result, "mfa-fatigue scenario");
    }

    @Test
    void recoveryAbuse_highRiskRecoveryRequestRoutesToStartRecoveryInsteadOfAnOutrightBlock() {
        RiskSignalEnvelope envelope = envelope(
                new RiskSignals(0, false, true, true, NetworkRiskLevel.LOW));

        ProtectionDecisionResult result = decide(
                "recovery-abuse", envelope, ProtectionEventType.PASSWORD_RESET_ATTEMPT);

        assertThat(result.riskScore()).isEqualTo(75);
        assertThat(reasonCodes(result)).containsExactlyInAnyOrder("COMPROMISED_CREDENTIAL", "IMPOSSIBLE_TRAVEL");
        // 75 would TEMPORARILY_BLOCK an ordinary login (stepUpMaxScore=69) but is within the
        // higher recovery-context threshold (recoveryMaxScore=89) -- routed into the recovery
        // flow's own gating (identity verification, delay, manual review) instead of an outright
        // block.
        assertThat(result.outcome()).isEqualTo(ProtectionOutcome.START_RECOVERY);
        assertThat(result.recoveryAuthorizationId()).isNotNull();

        report("Recovery abuse", "password-reset attempt, compromised credential, impossible travel",
                75, result, List.of("COMPROMISED_CREDENTIAL", "IMPOSSIBLE_TRAVEL"),
                "START_RECOVERY",
                "Recovery authorization " + result.recoveryAuthorizationId() + " issued, gated by recovery flow.");
        exportAndVerifyEvidence(result, "recovery-abuse scenario");
    }

    private ProtectionDecisionResult decide(
            String scenarioSlug, RiskSignalEnvelope envelope, ProtectionEventType eventType) {
        return protectionDecisionService.decide(new ProtectionDecisionCommand(
                "scenario-" + scenarioSlug + "-" + UUID.randomUUID() + "@example.test",
                eventType,
                envelope,
                "idem-" + scenarioSlug + "-" + UUID.randomUUID()));
    }

    private RiskSignalEnvelope envelope(RiskSignals signals) {
        return new RiskSignalEnvelope(
                signals, "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true);
    }

    private List<String> reasonCodes(ProtectionDecisionResult result) {
        return result.reasons().stream().map(RiskReason::code).toList();
    }

    private void report(
            String name, String inputsSummary, int expectedScore, ProtectionDecisionResult result,
            List<String> expectedReasonCodes, String expectedOutcome, String notes) {
        ScenarioReportCollector.record(new ScenarioReport(
                name, inputsSummary, expectedScore, result.riskScore(),
                expectedReasonCodes, reasonCodes(result), expectedOutcome, result.outcome().name(),
                result.policyKey(), result.policyVersion(), notes));
    }

    private void exportAndVerifyEvidence(ProtectionDecisionResult result, String reason) {
        EvidenceBundle bundle = evidenceBundleService.exportBundle(new EvidenceExportCommand(
                result.protectionRequestId(), "scenario-lab", reason)).orElseThrow();
        EvidenceVerificationResult verification = evidenceBundleService.verify(bundle);
        assertThat(verification.valid())
                .as("evidence bundle for scenario decision %s must verify cleanly: %s",
                        result.decisionId(), verification.problems())
                .isTrue();
    }
}
