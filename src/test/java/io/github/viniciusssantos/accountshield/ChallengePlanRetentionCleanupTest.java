package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.challenge.internal.ChallengePlanRetentionCleanup;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class ChallengePlanRetentionCleanupTest {

    @Autowired private ChallengePlanRetentionCleanup retentionCleanup;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void purgesOnlyExpiredTerminalChallenges() {
        UUID expiredConsumed = insertChallenge("CONSUMED", OffsetDateTime.now(ZoneOffset.UTC).minusDays(2));
        UUID recentConsumed = insertChallenge("CONSUMED", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        UUID expiredButChallenged = insertChallenge("CHALLENGED", OffsetDateTime.now(ZoneOffset.UTC).minusDays(2));

        retentionCleanup.purgeExpiredTerminalChallenges();

        assertThat(challengeExists(expiredConsumed)).isFalse();
        assertThat(challengeExists(recentConsumed)).isTrue();
        assertThat(challengeExists(expiredButChallenged)).isTrue();
    }

    private boolean challengeExists(UUID id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM challenge.challenge_plan WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    private UUID insertChallenge(String status, OffsetDateTime expiresAt) {
        UUID id = UUID.randomUUID();
        OffsetDateTime consumedAt = "CONSUMED".equals(status) ? expiresAt : null;

        jdbcTemplate.update(
                """
                INSERT INTO challenge.challenge_plan (
                    id, account_reference, challenge_type, purpose, context_id, status,
                    max_attempts, remaining_attempts, code_hash, created_at, expires_at, consumed_at
                ) VALUES (?, ?, 'EMAIL_SIMULATED', 'RECOVERY_IDENTITY', ?, ?, 3, 3, ?, ?, ?, ?)
                """,
                id,
                "acct-retention-" + id,
                UUID.randomUUID(),
                status,
                "0".repeat(64),
                expiresAt.minusMinutes(10),
                expiresAt,
                consumedAt);

        return id;
    }
}
