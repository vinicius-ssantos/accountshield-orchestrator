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

    @Autowired
    private RecoveryService recoveryService;

    @Autowired
    private ChallengeService challengeService;

    @Autowired
    private DecisionTraceRecorder decisionTraceRecorder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void score30CompletesImmediatelyAfterVerifiedIdentity() {
        RecoveryFlow initiated = initiateFlow(30);

        assertThat(initiated.classification()).isEqualTo(RecoveryRiskClassification.IMMEDIATE);
        assertThat(initiated.status()).isEqualTo(RecoveryStatus.VERIFYING_IDENTITY);
        assertThat(initiated.eligibleAfter()).isNull();

        RecoveryFlow confirmed = verifyAndConfirmIdentity(initiated);
        assertThat(confirmed.status()).isEqualTo(RecoveryStatus.IDENTITY_VERIFIED);

        RecoveryFlow completed = recoveryService.complete(initiated.recoveryId());
        assertThat(completed.status()).isEqualTo(RecoveryStatus.COMPLETED);
        assertPersistedStatus(initiated.recoveryId(), RecoveryStatus.COMPLETED);

        RecoveryFlow equivalentRetry = recoveryService.complete(initiated.recoveryId());
        assertThat(equivalentRetry.status()).isEqualTo(RecoveryStatus.COMPLETED);
        assertThatThrownBy(() -> recoveryService.review(new RecoveryReviewCommand(
                initiated.recoveryId(), RecoveryReviewDecision.APPROVE, "operator-after-complete")))
                .isInstanceOf(InvalidRecoveryStateException.class);
    }

    @Test
    void score31EntersDelayAndCannotCompleteBeforeEligibility() {
        assertDelayedBoundary(31);
    }

    @Test
    void score60RemainsDelayedAtUpperBoundary() {
        assertDelayedBoundary(60);
    }

    @Test
    void score61RequiresApprovedManualReview() {
        RecoveryFlow initiated = initiateFlow(61);

        assertThat(initiated.classification()).isEqualTo(RecoveryRiskClassification.MANUAL_REVIEW);
        assertThat(initiated.eligibleAfter()).isNull();

        RecoveryFlow confirmed = verifyAndConfirmIdentity(initiated);
        assertThat(confirmed.status()).isEqualTo(RecoveryStatus.MANUAL_REVIEW);
        assertPersistedStatus(initiated.recoveryId(), RecoveryStatus.MANUAL_REVIEW);

        assertThatThrownBy(() -> recoveryService.complete(initiated.recoveryId()))
                .isInstanceOf(InvalidRecoveryStateException.class);

        RecoveryFlow approved = recoveryService.review(new RecoveryReviewCommand(
                initiated.recoveryId(), RecoveryReviewDecision.APPROVE, "operator-approver"));
        assertThat(approved.status()).isEqualTo(RecoveryStatus.COMPLETED);
        assertPersistedStatus(initiated.recoveryId(), RecoveryStatus.COMPLETED);

        RecoveryFlow equivalentCompletionRetry = recoveryService.complete(initiated.recoveryId());
        assertThat(equivalentCompletionRetry.status()).isEqualTo(RecoveryStatus.COMPLETED);
        assertThatThrownBy(() -> recoveryService.review(new RecoveryReviewCommand(
                initiated.recoveryId(), RecoveryReviewDecision.REJECT, "operator-late-review")))
                .isInstanceOf(InvalidRecoveryStateException.class);
    }

    @Test
    void rejectedManualReviewIsTerminal() {
        RecoveryFlow initiated = initiateFlow(61);
        RecoveryFlow confirmed = verifyAndConfirmIdentity(initiated);
        assertThat(confirmed.status()).isEqualTo(RecoveryStatus.MANUAL_REVIEW);

        RecoveryFlow rejected = recoveryService.review(new RecoveryReviewCommand(
                initiated.recoveryId(), RecoveryReviewDecision.REJECT, "operator-rejector"));
        assertThat(rejected.status()).isEqualTo(RecoveryStatus.REJECTED);
        assertPersistedStatus(initiated.recoveryId(), RecoveryStatus.REJECTED);

        assertThatThrownBy(() -> recoveryService.complete(initiated.recoveryId()))
                .isInstanceOf(InvalidRecoveryStateException.class);
        assertThatThrownBy(() -> recoveryService.review(new RecoveryReviewCommand(
                initiated.recoveryId(), RecoveryReviewDecision.APPROVE, "operator-reopen")))
                .isInstanceOf(InvalidRecoveryStateException.class);
        assertThatThrownBy(() -> recoveryService.confirmIdentity(new ConfirmIdentityCommand(
                initiated.recoveryId(), initiated.identityChallengeId())))
                .isInstanceOf(InvalidRecoveryStateException.class);
    }

    @Test
    void rejectsInitiationWhenProtectionRequestDoesNotExist() {
        assertThatThrownBy(() -> recoveryService.initiate(new InitiateRecoveryCommand(
                UUID.randomUUID(), RecoveryEventType.LOGIN)))
                .isInstanceOf(UnauthorizedRecoveryInitiationException.class);
    }

    private void assertDelayedBoundary(int riskScore) {
        RecoveryFlow initiated = initiateFlow(riskScore);

        assertThat(initiated.classification()).isEqualTo(RecoveryRiskClassification.DELAYED);
        assertThat(initiated.eligibleAfter()).isNotNull();

        RecoveryFlow confirmed = verifyAndConfirmIdentity(initiated);
        assertThat(confirmed.status()).isEqualTo(RecoveryStatus.DELAYED);
        assertPersistedStatus(initiated.recoveryId(), RecoveryStatus.DELAYED);

        assertThatThrownBy(() -> recoveryService.complete(initiated.recoveryId()))
                .isInstanceOf(InvalidRecoveryStateException.class);

        int updated = jdbcTemplate.update(
                "UPDATE recovery.recovery_flow "
                        + "SET eligible_after = CURRENT_TIMESTAMP - INTERVAL '1 second' "
                        + "WHERE id = ?",
                initiated.recoveryId());
        assertThat(updated).isEqualTo(1);

        RecoveryFlow completed = recoveryService.complete(initiated.recoveryId());
        assertThat(completed.status()).isEqualTo(RecoveryStatus.COMPLETED);
        assertPersistedStatus(initiated.recoveryId(), RecoveryStatus.COMPLETED);

        RecoveryFlow equivalentRetry = recoveryService.complete(initiated.recoveryId());
        assertThat(equivalentRetry.status()).isEqualTo(RecoveryStatus.COMPLETED);
    }

    private RecoveryFlow initiateFlow(int riskScore) {
        UUID protectionRequestId = recordDecisionTrace(riskScore);
        return recoveryService.initiate(new InitiateRecoveryCommand(
                protectionRequestId, RecoveryEventType.PASSWORD_RESET));
    }

    private RecoveryFlow verifyAndConfirmIdentity(RecoveryFlow flow) {
        String expectedCode = jdbcTemplate.queryForObject(
                "SELECT expected_code FROM challenge.challenge_plan WHERE id = ?",
                String.class,
                flow.identityChallengeId());

        var verification = challengeService.verify(new ChallengeVerificationCommand(
                flow.identityChallengeId(),
                expectedCode,
                ChallengePurpose.RECOVERY_IDENTITY,
                flow.recoveryId()));
        assertThat(verification.status()).isEqualTo(ChallengeStatus.VERIFIED);
        assertThat(verification.verified()).isTrue();

        RecoveryFlow confirmed = recoveryService.confirmIdentity(new ConfirmIdentityCommand(
                flow.recoveryId(), flow.identityChallengeId()));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM challenge.challenge_plan WHERE id = ?",
                String.class,
                flow.identityChallengeId())).isEqualTo("CONSUMED");
        return confirmed;
    }

    private UUID recordDecisionTrace(int riskScore) {
        UUID protectionRequestId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored ->
                decisionTraceRecorder.record(new DecisionTraceCommand(
                        UUID.randomUUID(),
                        protectionRequestId,
                        "recovery-boundary-" + UUID.randomUUID(),
                        "fingerprint-" + UUID.randomUUID(),
                        "risk-rules-1.0",
                        "account-protection-default",
                        "1.0.0",
                        "REQUIRE_STEP_UP",
                        riskScore,
                        Map.of("fixture", "recovery-classification-boundary"),
                        Instant.now(),
                        List.of())));
        return protectionRequestId;
    }

    private void assertPersistedStatus(UUID recoveryId, RecoveryStatus expected) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM recovery.recovery_flow WHERE id = ?",
                String.class,
                recoveryId)).isEqualTo(expected.name());
    }
}
