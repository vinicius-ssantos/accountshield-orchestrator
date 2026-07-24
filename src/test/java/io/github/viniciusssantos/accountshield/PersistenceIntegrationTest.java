package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
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
        insertPolicyVersion(id, "default-recovery", "2026.07.1", "ACTIVE", OffsetDateTime.now(ZoneOffset.UTC));

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE policy.policy_version SET definition = '{}'::jsonb WHERE id = ?",
                        id))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
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
                OffsetDateTime.now(ZoneOffset.UTC));

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE audit.decision_trace SET outcome = 'MONITOR' WHERE id = ?",
                        decisionId))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("audit records are append-only");
    }

    @Test
    void rejectsRecoveryFlowWithoutAValidOriginatingProtectionRequest() {
        UUID authorizationId = insertRecoveryAuthorization();

        assertThatThrownBy(() -> insertRecoveryFlow(UUID.randomUUID(), authorizationId, UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsRecoveryFlowWithNullProtectionRequestId() {
        UUID authorizationId = insertRecoveryAuthorization();

        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO recovery.recovery_flow (
                            id, account_reference, event_type, status, classification,
                            risk_score, initiated_at, updated_at, protection_request_id,
                            originating_decision_id, authorization_id
                        ) VALUES (?, ?, 'LOGIN', 'VERIFYING_IDENTITY', 'IMMEDIATE', 10, ?, ?, NULL, ?, ?)
                        """,
                        UUID.randomUUID(),
                        "acct-null-request",
                        OffsetDateTime.now(ZoneOffset.UTC),
                        OffsetDateTime.now(ZoneOffset.UTC),
                        UUID.randomUUID(),
                        authorizationId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsDuplicateProtectionRequestIdAcrossRecoveryFlows() {
        UUID protectionRequestId = insertProtectionRequest();
        UUID firstAuthorizationId = insertRecoveryAuthorization();
        UUID secondAuthorizationId = insertRecoveryAuthorization();

        insertRecoveryFlow(protectionRequestId, firstAuthorizationId, UUID.randomUUID());

        assertThatThrownBy(() -> insertRecoveryFlow(protectionRequestId, secondAuthorizationId, UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }

    private UUID insertProtectionRequest() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO protection.protection_request (
                    id, account_reference, event_type, request_fingerprint, status, requested_at
                ) VALUES (?, ?, 'LOGIN', ?, 'DECIDED', ?)
                """,
                id,
                "acct-persistence-" + id,
                "fingerprint-" + id,
                OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    private UUID insertRecoveryAuthorization() {
        UUID id = UUID.randomUUID();
        OffsetDateTime issuedAt = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO recovery.recovery_authorization (
                    id, protection_request_id, decision_id, account_reference, directive,
                    risk_score, issued_at, expires_at, consumed_at
                ) VALUES (?, ?, ?, ?, 'LOGIN', 10, ?, ?, NULL)
                """,
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "acct-persistence-" + id,
                issuedAt,
                issuedAt.plusSeconds(600));
        return id;
    }

    private void insertRecoveryFlow(UUID protectionRequestId, UUID authorizationId, UUID originatingDecisionId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO recovery.recovery_flow (
                    id, account_reference, event_type, status, classification,
                    risk_score, initiated_at, updated_at, protection_request_id,
                    originating_decision_id, authorization_id
                ) VALUES (?, ?, 'LOGIN', 'VERIFYING_IDENTITY', 'IMMEDIATE', 10, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                "acct-persistence-flow",
                now,
                now,
                protectionRequestId,
                originatingDecisionId,
                authorizationId);
    }

    private void insertIdempotencyRecord(UUID id, String key, String fingerprint) {
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
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
            OffsetDateTime activatedAt) {
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
                OffsetDateTime.now(ZoneOffset.UTC),
                activatedAt);
    }
}
