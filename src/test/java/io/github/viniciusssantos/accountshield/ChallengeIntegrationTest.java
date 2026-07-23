package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.challenge.ChallengePlan;
import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeResult;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.challenge.ChallengeUseRejectedException;
import io.github.viniciusssantos.accountshield.challenge.ChallengeVerificationCommand;
import io.github.viniciusssantos.accountshield.challenge.ConsumeChallengeCommand;
import io.github.viniciusssantos.accountshield.challenge.CreateChallengeCommand;
import io.github.viniciusssantos.accountshield.challenge.InvalidChallengeStateException;
import io.github.viniciusssantos.accountshield.challenge.internal.persistence.ChallengePlanRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class ChallengeIntegrationTest {

    @Autowired
    private ChallengeService challengeService;

    @Autowired
    private ChallengePlanRepository challengePlanRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    void createsVerifiesAndConsumesBoundChallenge() {
        UUID contextId = UUID.randomUUID();
        String accountReference = "integration-challenge-" + UUID.randomUUID();
        ChallengePlan plan = create(
                accountReference,
                ChallengeType.TOTP_SIMULATED,
                ChallengePurpose.PROTECTION_STEP_UP,
                contextId);
        challengePlanRepository.flush();

        assertThat(plan.challengeId()).isNotNull();
        assertThat(plan.purpose()).isEqualTo(ChallengePurpose.PROTECTION_STEP_UP);
        assertThat(plan.contextId()).isEqualTo(contextId);
        assertThat(plan.status()).isEqualTo(ChallengeStatus.CHALLENGED);

        String code = expectedCode(plan.challengeId());
        ChallengeResult result = challengeService.verify(new ChallengeVerificationCommand(
                plan.challengeId(),
                code,
                ChallengePurpose.PROTECTION_STEP_UP,
                contextId));
        challengePlanRepository.flush();

        assertThat(result.verified()).isTrue();
        assertThat(result.status()).isEqualTo(ChallengeStatus.VERIFIED);

        ChallengePlan consumed = challengeService.consume(new ConsumeChallengeCommand(
                plan.challengeId(),
                accountReference,
                ChallengePurpose.PROTECTION_STEP_UP,
                contextId));
        challengePlanRepository.flush();

        assertThat(consumed.status()).isEqualTo(ChallengeStatus.CONSUMED);
        assertThat(consumed.consumedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM challenge.challenge_plan WHERE id = ?",
                String.class,
                plan.challengeId())).isEqualTo("CONSUMED");
    }

    @Test
    @Transactional
    void rejectsVerificationForDifferentPurposeOrContext() {
        UUID contextId = UUID.randomUUID();
        ChallengePlan plan = create(
                "integration-binding-" + UUID.randomUUID(),
                ChallengeType.TOTP_SIMULATED,
                ChallengePurpose.PROTECTION_STEP_UP,
                contextId);
        challengePlanRepository.flush();

        assertThatThrownBy(() -> challengeService.verify(new ChallengeVerificationCommand(
                plan.challengeId(),
                expectedCode(plan.challengeId()),
                ChallengePurpose.RECOVERY_IDENTITY,
                contextId)))
                .isInstanceOf(ChallengeUseRejectedException.class);

        assertThatThrownBy(() -> challengeService.verify(new ChallengeVerificationCommand(
                plan.challengeId(),
                expectedCode(plan.challengeId()),
                ChallengePurpose.PROTECTION_STEP_UP,
                UUID.randomUUID())))
                .isInstanceOf(ChallengeUseRejectedException.class);
    }

    @Test
    void concurrentConsumptionHasExactlyOneWinner() throws Exception {
        UUID contextId = UUID.randomUUID();
        String accountReference = "integration-concurrent-" + UUID.randomUUID();
        ChallengePlan plan = create(
                accountReference,
                ChallengeType.WEBAUTHN_SIMULATED,
                ChallengePurpose.RECOVERY_IDENTITY,
                contextId);
        challengePlanRepository.flush();

        challengeService.verify(new ChallengeVerificationCommand(
                plan.challengeId(),
                expectedCode(plan.challengeId()),
                ChallengePurpose.RECOVERY_IDENTITY,
                contextId));
        challengePlanRepository.flush();

        int contenderCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(contenderCount);
        CountDownLatch ready = new CountDownLatch(contenderCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int index = 0; index < contenderCount; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        challengeService.consume(new ConsumeChallengeCommand(
                                plan.challengeId(),
                                accountReference,
                                ChallengePurpose.RECOVERY_IDENTITY,
                                contextId));
                        return true;
                    } catch (ChallengeUseRejectedException | InvalidChallengeStateException exception) {
                        return false;
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long winnerCount = 0;
            for (Future<Boolean> result : results) {
                if (result.get(15, TimeUnit.SECONDS)) {
                    winnerCount++;
                }
            }
            assertThat(winnerCount).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM challenge.challenge_plan WHERE id = ?",
                String.class,
                plan.challengeId())).isEqualTo("CONSUMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT consumed_at IS NOT NULL FROM challenge.challenge_plan WHERE id = ?",
                Boolean.class,
                plan.challengeId())).isTrue();
    }

    @Test
    @Transactional
    void failsAfterExhaustingAttempts() {
        UUID contextId = UUID.randomUUID();
        ChallengePlan plan = create(
                "integration-fail-" + UUID.randomUUID(),
                ChallengeType.EMAIL_SIMULATED,
                ChallengePurpose.PROTECTION_STEP_UP,
                contextId);
        challengePlanRepository.flush();

        challengeService.verify(verification(plan, "wrong1"));
        challengeService.verify(verification(plan, "wrong2"));
        ChallengeResult result = challengeService.verify(verification(plan, "wrong3"));
        challengePlanRepository.flush();

        assertThat(result.verified()).isFalse();
        assertThat(result.status()).isEqualTo(ChallengeStatus.FAILED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM challenge.challenge_plan WHERE id = ?",
                String.class,
                plan.challengeId())).isEqualTo("FAILED");
    }

    @Test
    @Transactional
    void rejectsVerificationOnExpiredChallenge() {
        UUID contextId = UUID.randomUUID();
        ChallengePlan plan = create(
                "integration-expired-" + UUID.randomUUID(),
                ChallengeType.WEBAUTHN_SIMULATED,
                ChallengePurpose.RECOVERY_IDENTITY,
                contextId);
        challengePlanRepository.flush();

        jdbcTemplate.update(
                "UPDATE challenge.challenge_plan SET created_at = NOW() - INTERVAL '11 minutes', expires_at = NOW() - INTERVAL '1 second' WHERE id = ?",
                plan.challengeId());
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> challengeService.verify(new ChallengeVerificationCommand(
                plan.challengeId(),
                "000000",
                ChallengePurpose.RECOVERY_IDENTITY,
                contextId)))
                .isInstanceOf(InvalidChallengeStateException.class);
    }

    @Test
    @Transactional
    void persistsBindingVersionAndType() {
        UUID contextId = UUID.randomUUID();
        ChallengePlan plan = create(
                "integration-schema-" + UUID.randomUUID(),
                ChallengeType.TOTP_SIMULATED,
                ChallengePurpose.PRIVILEGED_OPERATION,
                contextId);
        challengePlanRepository.flush();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT challenge_type FROM challenge.challenge_plan WHERE id = ?",
                String.class,
                plan.challengeId())).isEqualTo("TOTP_SIMULATED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT purpose FROM challenge.challenge_plan WHERE id = ?",
                String.class,
                plan.challengeId())).isEqualTo("PRIVILEGED_OPERATION");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT context_id FROM challenge.challenge_plan WHERE id = ?",
                UUID.class,
                plan.challengeId())).isEqualTo(contextId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM challenge.challenge_plan WHERE id = ?",
                Long.class,
                plan.challengeId())).isZero();
    }

    private ChallengePlan create(
            String accountReference,
            ChallengeType type,
            ChallengePurpose purpose,
            UUID contextId) {
        return challengeService.create(new CreateChallengeCommand(
                accountReference,
                type,
                purpose,
                contextId));
    }

    private ChallengeVerificationCommand verification(ChallengePlan plan, String code) {
        return new ChallengeVerificationCommand(
                plan.challengeId(),
                code,
                plan.purpose(),
                plan.contextId());
    }

    private String expectedCode(UUID challengeId) {
        return jdbcTemplate.queryForObject(
                "SELECT expected_code FROM challenge.challenge_plan WHERE id = ?",
                String.class,
                challengeId);
    }
}
