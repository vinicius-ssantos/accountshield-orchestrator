package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the accountshield_runtime role's grants (migration V20) are correct by connecting as
 * that role directly -- bypassing Spring's own (owner-privileged, in tests) managed datasource
 * entirely -- rather than by switching the application's own connection, which would require
 * restructuring how every integration test in this suite connects (see ADR 0024).
 */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class DatabaseRolePermissionIntegrationTest {

    private static final String RUNTIME_USER = "accountshield_runtime";
    private static final String RUNTIME_PASSWORD = "accountshield-local-only-runtime";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void runtimeRoleCanInsertIntoRegularTables() throws Exception {
        try (Connection connection = runtimeConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO protection.protection_request "
                                + "(id, account_reference, event_type, request_fingerprint, status, requested_at) "
                                + "VALUES (?, ?, 'LOGIN', ?, 'DECIDED', ?)")) {
            UUID id = UUID.randomUUID();
            statement.setObject(1, id);
            statement.setString(2, "acct-role-test-" + id);
            statement.setString(3, "fingerprint-" + id);
            statement.setObject(4, OffsetDateTime.now(ZoneOffset.UTC));

            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    @Test
    void runtimeRoleCannotUpdateAuditRows() throws Exception {
        UUID protectionRequestId = insertProtectionRequestAsOwner();
        UUID decisionId = insertDecisionTraceAsOwner(protectionRequestId);

        try (Connection connection = runtimeConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE audit.decision_trace SET outcome = 'MONITOR' WHERE id = ?")) {
            statement.setObject(1, decisionId);

            assertThatThrownBy(statement::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void runtimeRoleCannotDeleteAuditRows() throws Exception {
        UUID protectionRequestId = insertProtectionRequestAsOwner();
        UUID decisionId = insertDecisionTraceAsOwner(protectionRequestId);

        try (Connection connection = runtimeConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM audit.decision_trace WHERE id = ?")) {
            statement.setObject(1, decisionId);

            assertThatThrownBy(statement::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void runtimeRoleCannotDropTheAuditImmutabilityFunction() throws Exception {
        // dropping requires ownership, not a grantable privilege -- Postgres reports this
        // differently ("must be owner of") than a GRANT/REVOKE-controlled DML denial
        try (Connection connection = runtimeConnection();
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute(
                            "DROP FUNCTION audit.reject_audit_mutation() CASCADE"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("must be owner");
        }
    }

    @Test
    void runtimeRoleCannotAlterTheAuditImmutabilityFunction() throws Exception {
        try (Connection connection = runtimeConnection();
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute(
                            "ALTER FUNCTION audit.reject_audit_mutation() OWNER TO " + RUNTIME_USER))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("must be owner");
        }
    }

    @Test
    void runtimeRoleCannotDropTheAuditImmutabilityTrigger() throws Exception {
        // dropping a trigger requires ownership of the table it's defined on
        try (Connection connection = runtimeConnection();
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute(
                            "DROP TRIGGER trg_decision_trace_append_only ON audit.decision_trace"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("must be owner");
        }
    }

    private Connection runtimeConnection() throws SQLException {
        String url = jdbcTemplate.execute((Connection connection) -> connection.getMetaData().getURL());
        return DriverManager.getConnection(url, RUNTIME_USER, RUNTIME_PASSWORD);
    }

    private UUID insertProtectionRequestAsOwner() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO protection.protection_request (
                    id, account_reference, event_type, request_fingerprint, status, requested_at
                ) VALUES (?, ?, 'LOGIN', ?, 'DECIDED', ?)
                """,
                id,
                "acct-role-test-" + id,
                "fingerprint-" + id,
                OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    private UUID insertDecisionTraceAsOwner(UUID protectionRequestId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO audit.decision_trace (
                    id, protection_request_id, account_reference, request_fingerprint,
                    algorithm_version, policy_key, policy_version, outcome, risk_score,
                    normalized_context, decided_at
                ) VALUES (?, ?, ?, ?, 'risk-rules-1.0', 'account-protection-default', '1.0.0',
                          'ALLOW', 10, '{}'::jsonb, ?)
                """,
                id,
                protectionRequestId,
                "acct-role-test-" + id,
                "fingerprint-decision-" + id,
                OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }
}
