package io.github.viniciusssantos.accountshield.audit.internal;

import io.github.viniciusssantos.accountshield.audit.DecisionReasonContribution;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceQuery;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class JdbcDecisionTraceQuery implements DecisionTraceQuery {

    private static final String SELECT_TRACE = """
            SELECT id, protection_request_id, account_reference, request_fingerprint,
                   algorithm_version, policy_key, policy_version, outcome, risk_score,
                   normalized_context::text, decided_at
              FROM audit.decision_trace
             WHERE protection_request_id = ?
            """;

    private static final String SELECT_REASONS = """
            SELECT code, contribution
              FROM audit.decision_reason
             WHERE decision_id = ?
             ORDER BY ordinal NULLS LAST, code
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcDecisionTraceQuery(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<DecisionTraceView> findByProtectionRequestId(UUID protectionRequestId) {
        Objects.requireNonNull(protectionRequestId, "protectionRequestId must not be null");

        List<DecisionTraceView> results = jdbcTemplate.query(
                SELECT_TRACE,
                (rs, rowNum) -> {
                    UUID decisionId = rs.getObject("id", UUID.class);
                    Map<String, Object> context = parseContext(rs.getString("normalized_context"));
                    List<DecisionReasonContribution> reasons = loadReasons(decisionId);
                    return new DecisionTraceView(
                            decisionId,
                            rs.getObject("protection_request_id", UUID.class),
                            rs.getString("account_reference"),
                            rs.getString("request_fingerprint"),
                            rs.getString("algorithm_version"),
                            rs.getString("policy_key"),
                            rs.getString("policy_version"),
                            rs.getString("outcome"),
                            rs.getShort("risk_score"),
                            context,
                            toInstant(rs.getTimestamp("decided_at")),
                            reasons);
                },
                protectionRequestId);

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    private Map<String, Object> parseContext(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<DecisionReasonContribution> loadReasons(UUID decisionId) {
        return jdbcTemplate.query(
                SELECT_REASONS,
                (rs, rowNum) -> new DecisionReasonContribution(
                        rs.getString("code"),
                        rs.getInt("contribution"),
                        Map.of()),
                decisionId);
    }

    private java.time.Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
