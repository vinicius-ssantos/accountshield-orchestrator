package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeVerificationCommand;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
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
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class RecoveryIntegrationTest {

    @Autowired private RecoveryService recoveryService;
    @Autowired private ChallengeService challengeService;
    @Autowired private ProtectionDecisionService protectionDecisionService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void protectionDecisionIssuesConsumableAuthorizationTransactionally() {
        String accountReference = "authorized-recovery-" + UUID.randomUUID();
        ProtectionDecisionResult decision = protectionDecisionService.decide(
                new ProtectionDecisionCommand(
                        accountReference,
                        ProtectionEventType.PASSWORD_RESET_ATTEMPT,
                        new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW),
                        "recovery-authorization-" + UUID.randomUUID()));

        assertThat(decision.outcome()).isEqualTo(ProtectionOutcome.START_RECOVERY);
        assertThat(decision.recoveryAuthorizationId()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT decision_id FROM recovery.recovery_authorization WHERE id = ?",
                UUID.class,
                decision.recoveryAuthorizationId())).isEqualTo(decision.decisionId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT directive FROM recovery.recovery_authorization WHERE id = ?",
                String.class,
                decision.recoveryAuthorizationId())).isEqualTo("PASSWORD_RESET");

        RecoveryFlow flow = recoveryService.initiate(
                new InitiateRecoveryCommand(decision.recoveryAuthorizationId()));

        assertThat(flow.authorizationId()).isEqualTo(decision.recoveryAuthorizationId());
        assertThat(flow.protectionRequestId()).isEqualTo(decision.protectionRequestId());
        assertThat(flow.originatingDecisionId()).isEqualTo(decision.decisionId());
        assertThat(flow.eventType()).isEqualTo(RecoveryEventType.PASSWORD_RESET);
    }

    @Test
    void existingAuthorizationWorksWithoutAuditProjection() {
        AuthorizationFixture fixture = createAuthorization(
                30, "LOGIN", Instant.now().plusSeconds(600));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit.decision_trace WHERE id = ?",
                Integer.class,
                fixture.decisionId())).isZero();

        RecoveryFlow flow = recoveryService.initiate(
                new InitiateRecoveryCommand(fixture.authorizationId()));

        assertThat(flow.authorizationId()).isEqualTo(fixture.authorizationId());
        assertThat(flow.originatingDecisionId()).isEqualTo(fixture.decisionId());
        assertThat(flow.eventType()).isEqualTo(RecoveryEventType.LOGIN);
    }

    @Test
    void score30CompletesImmediatelyAfterVerifiedIdentity() {
        RecoveryFlow initiated = initiateFlow(30, "PASSWORD_RESET");

        assertThat(initiated.classification()).isEqualTo(RecoveryRiskClassification.IMMEDIATE);
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
        RecoveryFlow initiated = initiateFlow(61, "CREDENTIAL_CHANGE");
        RecoveryFlow confirmed = verifyAndConfirmIdentity(initiated);

        assertThat(confirmed.status()).isEqualTo(RecoveryStatus.MANUAL_REVIEW);
        assertThatThrownBy(() -> recoveryService.complete(initiated.recoveryId()))
                .isInstanceOf(InvalidRecoveryStateException.class);

        RecoveryFlow approved = recoveryService.review(new RecoveryReviewCommand(
                initiated.recoveryId(), RecoveryReviewDecision.APPROVE, "operator-approver"));
        assertThat(approved.status()).isEqualTo(RecoveryStatus.COMPLETED);
    }

    @Test
    void duplicateInitiationReturnsSameFlowAndDoesNotCreateAnotherChallenge() {
        AuthorizationFixture fixture = createAuthorization(
                45, "DEVICE_TRUST_RESET", Instant.now().plusSeconds(600));

        RecoveryFlow first = recoveryService.initiate(
                new InitiateRecoveryCommand(fixture.authorizationId()));
        RecoveryFlow retry = recoveryService.initiate(
                new InitiateRecoveryCommand(fixture.authorizationId()));

        assertThat(retry.recoveryId()).isEqualTo(first.recoveryId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recovery.recovery_flow WHERE authorization_id = ?",
                Integer.class,
                fixture.authorizationId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM challenge.challenge_plan WHERE context_id = ?",
                Integer.class,
                first.recoveryId())).isEqualTo(1);
    }

    @Test
    void expiredAndMissingAuthorizationsShareGenericFailure() {
        AuthorizationFixture expired = createAuthorization(
                30, "LOGIN", Instant.now().minusSeconds(1));

        assertGenericAuthorizationFailure(expired.authorizationId());
        assertGenericAuthorizationFailure(UUID.randomUUID());
    }

    @Test
    void authorizationFieldsCannotBeMutatedAfterIssuance() {
        AuthorizationFixture fixture = createAuthorization(
                30, "PASSWORD_RESET", Instant.now().plusSeconds(600));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE recovery.recovery_authorization SET directive = 'LOGIN' WHERE id = ?",
                fixture.authorizationId()))
                .isInstanceOf(DataAccessException.class);
    }

    private void assertDelayedBoundary(int riskScore) {
        RecoveryFlow initiated = initiateFlow(riskScore, "PASSWORD_RESET");
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

    private RecoveryFlow initiateFlow(int riskScore, String directive) {
        AuthorizationFixture fixture = createAuthorization(
                riskScore, directive, Instant.now().plusSeconds(600));
        return recoveryService.initiate(new InitiateRecoveryCommand(fixture.authorizationId()));
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

        return recoveryService.confirmIdentity(new ConfirmIdentityCommand(
                flow.recoveryId(), flow.identityChallengeId()));
    }

    private AuthorizationFixture createAuthorization(
            int riskScore,
            String directive,
            Instant expiresAt) {
        UUID authorizationId = UUID.randomUUID();
        UUID protectionRequestId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        Instant issuedAt = Instant.now().minus(30, ChronoUnit.SECONDS);

        jdbcTemplate.update(
                "INSERT INTO recovery.recovery_authorization "
                        + "(id, protection_request_id, decision_id, account_reference, directive, "
                        + "risk_score, issued_at, expires_at, consumed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                authorizationId,
                protectionRequestId,
                decisionId,
                "authorization-fixture-" + UUID.randomUUID(),
                directive,
                riskScore,
                Timestamp.from(issuedAt),
                Timestamp.from(expiresAt));

        return new AuthorizationFixture(authorizationId, protectionRequestId, decisionId);
    }

    private void assertGenericAuthorizationFailure(UUID authorizationId) {
        assertThatThrownBy(() -> recoveryService.initiate(
                new InitiateRecoveryCommand(authorizationId)))
                .isInstanceOf(UnauthorizedRecoveryInitiationException.class)
                .hasMessage("recovery authorization is invalid or unavailable");
    }

    private record AuthorizationFixture(
            UUID authorizationId,
            UUID protectionRequestId,
            UUID decisionId) {
    }
}
