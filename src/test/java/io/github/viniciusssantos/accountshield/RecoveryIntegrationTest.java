package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.audit.DecisionTraceCommand;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceRecorder;
import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeVerificationCommand;
import io.github.viniciusssantos.accountshield.recovery.ConfirmIdentityCommand;
import io.github.viniciusssantos.accountshield.recovery.InitiateRecoveryCommand;
import io.github.viniciusssantos.accountshield.recovery.InvalidRecoveryStateException;
import io.github.viniciusssantos.accountshield.recovery.RecoveryEventType;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlow;
import io.github.viniciusssantos.accountshield.recovery.RecoveryRiskClassification;
import io.github.viniciusssantos.accountshield.recovery.RecoveryReviewCommand;
import io.github.viniciusssantos.accountshield.recovery.RecoveryReviewDecision;
import io.github.viniciusssantos.accountshield.recovery.RecoveryService;
import io.github.viniciusssantos.accountshield.recovery.RecoveryStatus;
import io.github.viniciusssantos.accountshield.recovery.UnauthorizedRecoveryInitiationException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class RecoveryIntegrationTest {

    @Autowired private RecoveryService recoveryService;
    @Autowired private ChallengeService challengeService;
    @Autowired private DecisionTraceRecorder decisionTraceRecorder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void score30CompletesImmediatelyAfterExplicitRecoveryDecision() {
        RecoveryFlow initiated = initiateFlow(30, "PASSWORD_RESET_ATTEMPT");

        assertThat(initiated.eventType()).isEqualTo(RecoveryEventType.PASSWORD_RESET);
        assertThat(initiated.classification()).isEqualTo(RecoveryRiskClassification.IMMEDIATE);
        assertThat(initiated.originatingDecisionId()).isNotNull();

        RecoveryFlow confirmed = verifyAndConfirmIdentity(initiated);
        assertThat(confirmed.status()).isEqualTo(RecoveryStatus.IDENTITY_VERIFIED);
        assertThat(recoveryService.complete(initiated.recoveryId()).status())
                .isEqualTo(RecoveryStatus.COMPLETED);
    }

    @Test
    void score31And60RemainDelayedUntilEligibility() {
        assertDelayedBoundary(31);
        assertDelayedBoundary(60);
    }

    @Test
    void score61RequiresOperatorReview() {
        RecoveryFlow initiated = initiateFlow(61, "CREDENTIAL_CHANGE_ATTEMPT");
        RecoveryFlow confirmed = verifyAndConfirmIdentity(initiated);

        assertThat(initiated.eventType()).isEqualTo(RecoveryEventType.CREDENTIAL_CHANGE);
        assertThat(confirmed.status()).isEqualTo(RecoveryStatus.MANUAL_REVIEW);
        assertThatThrownBy(() -> recoveryService.complete(initiated.recoveryId()))
                .isInstanceOf(InvalidRecoveryStateException.class);

        RecoveryFlow approved = recoveryService.review(new RecoveryReviewCommand(
                initiated.recoveryId(), RecoveryReviewDecision.APPROVE, "operator-approver"));
        assertThat(approved.status()).isEqualTo(RecoveryStatus.COMPLETED);
    }

    @Test
    void rejectedManualReviewIsTerminal() {
        RecoveryFlow initiated = initiateFlow(61, "DEVICE_TRUST_RESET_ATTEMPT");
        verifyAndConfirmIdentity(initiated);

        RecoveryFlow rejected = recoveryService.review(new RecoveryReviewCommand(
                initiated.recoveryId(), RecoveryReviewDecision.REJECT, "operator-rejector"));
        assertThat(rejected.status()).isEqualTo(RecoveryStatus.REJECTED);
        assertThatThrownBy(() -> recoveryService.complete(initiated.recoveryId()))
                .isInstanceOf(InvalidRecoveryStateException.class);
        assertThatThrownBy(() -> recoveryService.review(new RecoveryReviewCommand(
                initiated.recoveryId(), RecoveryReviewDecision.APPROVE, "operator-reopen")))
                .isInstanceOf(InvalidRecoveryStateException.class);
    }

    @Test
    void rejectsEveryOutcomeOtherThanStartRecoveryWithSameGenericError() {
        for (String outcome : List.of("ALLOW", "REQUIRE_STEP_UP", "TEMPORARILY_BLOCK")) {
            UUID protectionRequestId = recordDecisionTrace(
                    45, outcome, "PASSWORD_RESET_ATTEMPT");

            assertThatThrownBy(() -> recoveryService.initiate(
                    new InitiateRecoveryCommand(protectionRequestId)))
                    .isInstanceOf(UnauthorizedRecoveryInitiationException.class)
                    .hasMessage("recovery authorization is invalid or unavailable");
        }
    }

    @Test
    void rejectsStartRecoveryWithIncompatibleOriginatingEvent() {
        UUID protectionRequestId = recordDecisionTrace(45, "START_RECOVERY", "LOGIN_ATTEMPT");

        assertThatThrownBy(() -> recoveryService.initiate(
                new InitiateRecoveryCommand(protectionRequestId)))
                .isInstanceOf(UnauthorizedRecoveryInitiationException.class)
                .hasMessage("recovery authorization is invalid or unavailable");
    }

    @Test
    void consumesOriginatingDecisionOnlyOnce() {
        UUID protectionRequestId = recordDecisionTrace(
                45, "START_RECOVERY", "LOGIN_RECOVERY_ATTEMPT");
        RecoveryFlow first = recoveryService.initiate(new InitiateRecoveryCommand(protectionRequestId));

        assertThat(first.eventType()).isEqualTo(RecoveryEventType.LOGIN);
        assertThatThrownBy(() -> recoveryService.initiate(
                new InitiateRecoveryCommand(protectionRequestId)))
                .isInstanceOf(UnauthorizedRecoveryInitiationException.class)
                .hasMessage("recovery authorization is invalid or unavailable");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recovery.recovery_flow WHERE originating_decision_id = ?",
                Integer.class, first.originatingDecisionId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM challenge.challenge_plan WHERE context_id = ?",
                Integer.class, first.recoveryId())).isEqualTo(1);
    }

    @Test
    void rejectsMissingAuthorizationGenerically() {
        assertThatThrownBy(() -> recoveryService.initiate(
                new InitiateRecoveryCommand(UUID.randomUUID())))
                .isInstanceOf(UnauthorizedRecoveryInitiationException.class)
                .hasMessage("recovery authorization is invalid or unavailable");
    }

    private void assertDelayedBoundary(int riskScore) {
        RecoveryFlow initiated = initiateFlow(riskScore, "PASSWORD_RESET_ATTEMPT");
        RecoveryFlow confirmed = verifyAndConfirmIdentity(initiated);

        assertThat(confirmed.status()).isEqualTo(RecoveryStatus.DELAYED);
        assertThatThrownBy(() -> recoveryService.complete(initiated.recoveryId()))
                .isInstanceOf(InvalidRecoveryStateException.class);

        jdbcTemplate.update(
                "UPDATE recovery.recovery_flow "
                        + "SET eligible_after = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?",
                initiated.recoveryId());
        assertThat(recoveryService.complete(initiated.recoveryId()).status())
                .isEqualTo(RecoveryStatus.COMPLETED);
    }

    private RecoveryFlow initiateFlow(int riskScore, String protectionEventType) {
        UUID protectionRequestId = recordDecisionTrace(
                riskScore, "START_RECOVERY", protectionEventType);
        return recoveryService.initiate(new InitiateRecoveryCommand(protectionRequestId));
    }

    private RecoveryFlow verifyAndConfirmIdentity(RecoveryFlow flow) {
        String expectedCode = jdbcTemplate.queryForObject(
                "SELECT expected_code FROM challenge.challenge_plan WHERE id = ?",
                String.class,
                flow.identityChallengeId());

        var verification = challengeService.verify(new ChallengeVerificationCommand(
                flow.identityChallengeId(), expectedCode,
                ChallengePurpose.RECOVERY_IDENTITY, flow.recoveryId()));
        assertThat(verification.status()).isEqualTo(ChallengeStatus.VERIFIED);

        return recoveryService.confirmIdentity(new ConfirmIdentityCommand(
                flow.recoveryId(), flow.identityChallengeId()));
    }

    private UUID recordDecisionTrace(
            int riskScore,
            String outcome,
            String protectionEventType) {
        UUID protectionRequestId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored ->
                decisionTraceRecorder.record(new DecisionTraceCommand(
                        UUID.randomUUID(),
                        protectionRequestId,
                        "recovery-authorization-" + UUID.randomUUID(),
                        "fingerprint-" + UUID.randomUUID(),
                        "risk-rules-1.0",
                        "account-protection-default",
                        "1.1.0",
                        outcome,
                        riskScore,
                        Map.of(
                                "protectionEventType", protectionEventType,
                                "recoveryRequest", true),
                        Instant.now(),
                        List.of())));
        return protectionRequestId;
    }
}
