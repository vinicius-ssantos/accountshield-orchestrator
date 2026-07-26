package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.recovery.internal.RecoveryAuthorizationRetentionCleanup;
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
class RecoveryAuthorizationRetentionCleanupTest {

    @Autowired private RecoveryAuthorizationRetentionCleanup retentionCleanup;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void purgesOnlyExpiredAuthorizations() {
        UUID expired = insertAuthorization(OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
        UUID recent = insertAuthorization(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

        retentionCleanup.purgeExpiredAuthorizations();

        assertThat(authorizationExists(expired)).isFalse();
        assertThat(authorizationExists(recent)).isTrue();
    }

    private boolean authorizationExists(UUID id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recovery.recovery_authorization WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    private UUID insertAuthorization(OffsetDateTime expiresAt) {
        UUID id = UUID.randomUUID();
        UUID protectionRequestId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        OffsetDateTime issuedAt = expiresAt.minusMinutes(10);

        jdbcTemplate.update(
                """
                INSERT INTO protection.protection_request (
                    id, account_reference, event_type, request_fingerprint, status, requested_at
                ) VALUES (?, ?, 'LOGIN', ?, 'DECIDED', ?)
                """,
                protectionRequestId,
                "acct-retention-" + id,
                "fingerprint-" + id,
                issuedAt);

        jdbcTemplate.update(
                """
                INSERT INTO audit.decision_trace (
                    id, protection_request_id, account_reference, request_fingerprint,
                    algorithm_version, policy_key, policy_version, outcome, risk_score,
                    normalized_context, decided_at
                ) VALUES (?, ?, ?, ?, 'risk-rules-1.0', 'account-protection-default', '1.0.0',
                          'START_RECOVERY', 10, '{}'::jsonb, ?)
                """,
                decisionId,
                protectionRequestId,
                "acct-retention-" + id,
                "fingerprint-decision-" + id,
                issuedAt);

        jdbcTemplate.update(
                """
                INSERT INTO recovery.recovery_authorization (
                    id, protection_request_id, decision_id, account_reference, directive,
                    risk_score, issued_at, expires_at, consumed_at
                ) VALUES (?, ?, ?, ?, 'LOGIN', 10, ?, ?, NULL)
                """,
                id,
                protectionRequestId,
                decisionId,
                "acct-retention-" + id,
                issuedAt,
                expiresAt);

        return id;
    }
}
