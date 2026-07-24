package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.recovery.internal.RecoveryFlowRetentionCleanup;
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
class RecoveryFlowRetentionCleanupTest {

    @Autowired private RecoveryFlowRetentionCleanup retentionCleanup;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void purgesOnlyExpiredTerminalFlows() {
        UUID expiredCompleted = insertFlow("COMPLETED", "IMMEDIATE", OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
        UUID recentCompleted = insertFlow("COMPLETED", "IMMEDIATE", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        UUID expiredButActive = insertFlow("MANUAL_REVIEW", "MANUAL_REVIEW", OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));

        retentionCleanup.purgeExpiredTerminalFlows();

        assertThat(flowExists(expiredCompleted)).isFalse();
        assertThat(flowExists(recentCompleted)).isTrue();
        assertThat(flowExists(expiredButActive)).isTrue();
    }

    private boolean flowExists(UUID id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recovery.recovery_flow WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    private UUID insertFlow(String status, String classification, OffsetDateTime updatedAt) {
        UUID id = UUID.randomUUID();
        UUID protectionRequestId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO protection.protection_request (
                    id, account_reference, event_type, request_fingerprint, status, requested_at
                ) VALUES (?, ?, 'LOGIN', ?, 'DECIDED', ?)
                """,
                protectionRequestId,
                "acct-retention-" + id,
                "fingerprint-" + id,
                updatedAt);

        jdbcTemplate.update(
                """
                INSERT INTO recovery.recovery_authorization (
                    id, protection_request_id, decision_id, account_reference, directive,
                    risk_score, issued_at, expires_at, consumed_at
                ) VALUES (?, ?, ?, ?, 'LOGIN', 10, ?, ?, NULL)
                """,
                authorizationId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "acct-retention-" + id,
                updatedAt,
                updatedAt.plusSeconds(600));

        jdbcTemplate.update(
                """
                INSERT INTO recovery.recovery_flow (
                    id, account_reference, event_type, status, classification,
                    risk_score, initiated_at, updated_at, protection_request_id,
                    originating_decision_id, authorization_id
                ) VALUES (?, ?, 'LOGIN', ?, ?, 10, ?, ?, ?, ?, ?)
                """,
                id,
                "acct-retention-" + id,
                status,
                classification,
                updatedAt,
                updatedAt,
                protectionRequestId,
                UUID.randomUUID(),
                authorizationId);

        return id;
    }
}
