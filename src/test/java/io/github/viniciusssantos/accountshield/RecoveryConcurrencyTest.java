package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeVerificationCommand;
import io.github.viniciusssantos.accountshield.recovery.ConfirmIdentityCommand;
import io.github.viniciusssantos.accountshield.recovery.InitiateRecoveryCommand;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlow;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowConflictException;
import io.github.viniciusssantos.accountshield.recovery.RecoveryReviewCommand;
import io.github.viniciusssantos.accountshield.recovery.RecoveryReviewDecision;
import io.github.viniciusssantos.accountshield.recovery.RecoveryService;
import io.github.viniciusssantos.accountshield.recovery.RecoveryStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class RecoveryConcurrencyTest {

    private static final int CONTENDER_COUNT = 8;

    @Autowired private RecoveryService recoveryService;
    @Autowired private ChallengeService challengeService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void concurrentInitiationWithSameAuthorizationConvergesOnOneFlow() throws Exception {
        UUID authorizationId = createAuthorization(30, "PASSWORD_RESET", Instant.now().plusSeconds(600));

        List<Future<UUID>> results = raceContenders(() ->
                recoveryService.initiate(new InitiateRecoveryCommand(authorizationId)).recoveryId());

        Set<UUID> recoveryIds = new HashSet<>();
        for (Future<UUID> result : results) {
            recoveryIds.add(result.get(15, TimeUnit.SECONDS));
        }

        assertThat(recoveryIds).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recovery.recovery_flow WHERE authorization_id = ?",
                Integer.class,
                authorizationId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM challenge.challenge_plan WHERE context_id = ?",
                Integer.class,
                recoveryIds.iterator().next())).isEqualTo(1);
    }

    @Test
    void concurrentReviewOnSameManualReviewFlowHasExactlyOneWinner() throws Exception {
        UUID authorizationId = createAuthorization(75, "CREDENTIAL_CHANGE", Instant.now().plusSeconds(600));
        RecoveryFlow initiated = recoveryService.initiate(new InitiateRecoveryCommand(authorizationId));

        String expectedCode = jdbcTemplate.queryForObject(
                "SELECT expected_code FROM challenge.challenge_plan WHERE id = ?",
                String.class,
                initiated.identityChallengeId());
        challengeService.verify(new ChallengeVerificationCommand(
                initiated.identityChallengeId(),
                expectedCode,
                ChallengePurpose.RECOVERY_IDENTITY,
                initiated.recoveryId()));
        RecoveryFlow readyForReview = recoveryService.confirmIdentity(
                new ConfirmIdentityCommand(initiated.recoveryId(), initiated.identityChallengeId()));
        assertThat(readyForReview.status()).isEqualTo(RecoveryStatus.MANUAL_REVIEW);

        List<Future<Boolean>> results = raceContenders(() -> {
            try {
                recoveryService.review(new RecoveryReviewCommand(
                        initiated.recoveryId(), RecoveryReviewDecision.APPROVE, "operator-race"));
                return true;
            } catch (RecoveryFlowConflictException exception) {
                return false;
            }
        });

        long winnerCount = 0;
        for (Future<Boolean> result : results) {
            if (result.get(15, TimeUnit.SECONDS)) {
                winnerCount++;
            }
        }
        assertThat(winnerCount).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM recovery.recovery_flow WHERE id = ?",
                String.class,
                initiated.recoveryId())).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM recovery.recovery_flow WHERE id = ?",
                Long.class,
                initiated.recoveryId())).isEqualTo(2L);
    }

    private <T> List<Future<T>> raceContenders(Callable<T> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONTENDER_COUNT);
        CountDownLatch ready = new CountDownLatch(CONTENDER_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> results = new ArrayList<>();
        try {
            for (int index = 0; index < CONTENDER_COUNT; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return action.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        } finally {
            executor.shutdown();
            executor.awaitTermination(20, TimeUnit.SECONDS);
        }
        return results;
    }

    private UUID createAuthorization(int riskScore, String directive, Instant expiresAt) {
        UUID authorizationId = UUID.randomUUID();
        UUID protectionRequestId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        Instant issuedAt = Instant.now().minus(30, ChronoUnit.SECONDS);

        jdbcTemplate.update(
                "INSERT INTO protection.protection_request "
                        + "(id, account_reference, event_type, request_fingerprint, status, requested_at) "
                        + "VALUES (?, ?, ?, ?, 'DECIDED', ?)",
                protectionRequestId,
                "concurrency-fixture-" + protectionRequestId,
                directive,
                "fingerprint-" + protectionRequestId,
                Timestamp.from(issuedAt));

        jdbcTemplate.update(
                "INSERT INTO recovery.recovery_authorization "
                        + "(id, protection_request_id, decision_id, account_reference, directive, "
                        + "risk_score, issued_at, expires_at, consumed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                authorizationId,
                protectionRequestId,
                decisionId,
                "concurrency-fixture-" + protectionRequestId,
                directive,
                riskScore,
                Timestamp.from(issuedAt),
                Timestamp.from(expiresAt));

        return authorizationId;
    }
}
