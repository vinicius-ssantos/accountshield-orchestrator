package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.challenge.ChallengePlan;
import io.github.viniciusssantos.accountshield.challenge.ChallengeResult;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.challenge.ChallengeVerificationCommand;
import io.github.viniciusssantos.accountshield.challenge.InvalidChallengeStateException;
import io.github.viniciusssantos.accountshield.challenge.internal.persistence.ChallengePlanRepository;
import jakarta.persistence.EntityManager;
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
    void createsAndVerifiesChallenge() {
        ChallengePlan plan = challengeService.create(
                "integration-challenge-" + java.util.UUID.randomUUID(),
                ChallengeType.TOTP_SIMULATED);
        challengePlanRepository.flush();

        assertThat(plan.challengeId()).isNotNull();
        assertThat(plan.status()).isEqualTo(ChallengeStatus.CHALLENGED);
        assertThat(plan.maxAttempts()).isEqualTo(3);

        String code = jdbcTemplate.queryForObject(
                "SELECT expected_code FROM challenge.challenge_plan WHERE id = ?",
                String.class, plan.challengeId());
        ChallengeResult result = challengeService.verify(
                new ChallengeVerificationCommand(plan.challengeId(), code));
        challengePlanRepository.flush();

        assertThat(result.verified()).isTrue();
        assertThat(result.status()).isEqualTo(ChallengeStatus.VERIFIED);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM challenge.challenge_plan WHERE id = ?",
                String.class, plan.challengeId())).isEqualTo("VERIFIED");
    }

    @Test
    @Transactional
    void failsAfterExhaustingAttempts() {
        ChallengePlan plan = challengeService.create(
                "integration-fail-" + java.util.UUID.randomUUID(),
                ChallengeType.EMAIL_SIMULATED);
        challengePlanRepository.flush();

        challengeService.verify(new ChallengeVerificationCommand(plan.challengeId(), "wrong1"));
        challengeService.verify(new ChallengeVerificationCommand(plan.challengeId(), "wrong2"));
        ChallengeResult result = challengeService.verify(new ChallengeVerificationCommand(plan.challengeId(), "wrong3"));
        challengePlanRepository.flush();

        assertThat(result.verified()).isFalse();
        assertThat(result.status()).isEqualTo(ChallengeStatus.FAILED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM challenge.challenge_plan WHERE id = ?",
                String.class, plan.challengeId())).isEqualTo("FAILED");
    }

    @Test
    @Transactional
    void rejectsVerificationOnExpiredChallenge() {
        ChallengePlan plan = challengeService.create(
                "integration-expired-" + java.util.UUID.randomUUID(),
                ChallengeType.WEBAUTHN_SIMULATED);
        challengePlanRepository.flush();

        jdbcTemplate.update(
                "UPDATE challenge.challenge_plan SET created_at = NOW() - INTERVAL '11 minutes', expires_at = NOW() - INTERVAL '1 second' WHERE id = ?",
                plan.challengeId());
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> challengeService.verify(
                new ChallengeVerificationCommand(plan.challengeId(), "000000")))
                .isInstanceOf(InvalidChallengeStateException.class);
    }

    @Test
    @Transactional
    void persistsCorrectSchemaAndType() {
        ChallengePlan plan = challengeService.create(
                "integration-schema-" + java.util.UUID.randomUUID(),
                ChallengeType.TOTP_SIMULATED);
        challengePlanRepository.flush();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT challenge_type FROM challenge.challenge_plan WHERE id = ?",
                String.class, plan.challengeId())).isEqualTo("TOTP_SIMULATED");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT max_attempts FROM challenge.challenge_plan WHERE id = ?",
                Integer.class, plan.challengeId())).isEqualTo(3);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT remaining_attempts FROM challenge.challenge_plan WHERE id = ?",
                Integer.class, plan.challengeId())).isEqualTo(3);
    }
}