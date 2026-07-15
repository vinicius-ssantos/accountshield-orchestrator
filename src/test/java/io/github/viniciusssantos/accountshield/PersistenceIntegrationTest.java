package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class PersistenceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsTheOwnedSchemasThroughFlyway() {
        List<String> schemas = jdbcTemplate.queryForList(
                """
                SELECT schema_name
                FROM information_schema.schemata
                WHERE schema_name IN ('protection', 'policy', 'audit', 'outbox')
                ORDER BY schema_name
                """,
                String.class);

        assertThat(schemas).containsExactly("audit", "outbox", "policy", "protection");
    }

    @Test
    void rejectsAnIdempotencyKeyReusedForAnotherRecord() {
        String key = "idem-" + UUID.randomUUID();
        insertIdempotencyRecord(UUID.randomUUID(), key, "a".repeat(64));

        assertThatThrownBy(() -> insertIdempotencyRecord(UUID.randomUUID(), key, "b".repeat(64)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsDuplicatePolicyVersions() {
        UUID first = UUID.randomUUID();
        insertPolicyVersion(first, "default-login", "2026.07.1", "DRAFT", null);

        assertThatThrownBy(() -> insertPolicyVersion(
                        UUID.randomUUID(), "default-login", "2026.07.1", "DRAFT", null))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void protectsActivatedPolicyVersionsFromMutation() {
        UUID id = UUID.randomUUID();
        insertPolicyVersion(id, "default-recovery", "2026.07.1", "ACTIVE", Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE policy.policy_version SET definition = '{}'::jsonb WHERE id = ?",
                        id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("activated policy versions are immutable");
    }

    @Test
    void keepsDecisionTracesAppendOnly() {
        UUID decisionId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO audit.decision_trace (
                    id, protection_request_id, account_reference, request_fingerprint,
                    algorithm_version, policy_key, policy_version, outcome, risk_score,
                    normalized_context, decided_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, ?)
                """,
                decisionId,
                UUID.randomUUID(),
                "acct-test",
                "c".repeat(64),
                "risk-1",
                "default-login",
                "2026.07.1",
                "ALLOW",
                10,
                Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE audit.decision_trace SET outcome = 'MONITOR' WHERE id = ?",
                        decisionId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit records are append-only");
    }

    private void insertIdempotencyRecord(UUID id, String key, String fingerprint) {
        Instant createdAt = Instant.now();
        jdbcTemplate.update(
                """
                INSERT INTO protection.idempotency_record (
                    id, idempotency_key, request_fingerprint, resource_type,
                    created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                id,
                key,
                fingerprint,
                "PROTECTION_DECISION",
                createdAt,
                createdAt.plusSeconds(300));
    }

    private void insertPolicyVersion(
            UUID id,
            String policyKey,
            String version,
            String status,
            Instant activatedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO policy.policy_version (
                    id, policy_key, version, status, definition, created_at, activated_at
                ) VALUES (?, ?, ?, ?, '{}'::jsonb, ?, ?)
                """,
                id,
                policyKey,
                version,
                status,
                Instant.now(),
                activatedAt);
    }
}
