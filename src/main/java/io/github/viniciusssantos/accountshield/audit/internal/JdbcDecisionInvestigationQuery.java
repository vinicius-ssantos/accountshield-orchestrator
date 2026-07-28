package io.github.viniciusssantos.accountshield.audit.internal;

import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionInvestigationCriteria;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionInvestigationPage;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionInvestigationSummary;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JdbcDecisionInvestigationQuery implements DecisionInvestigationQuery {

    private static final String BASE_QUERY = """
            SELECT dt.id,
                   dt.correlation_id,
                   pr.event_type,
                   dt.outcome,
                   dt.risk_score,
                   dt.policy_key,
                   dt.policy_version,
                   dt.decided_at,
                   CASE
                       WHEN dt.normalized_context ->> 'degraded' IN ('true', 'false')
                           THEN (dt.normalized_context ->> 'degraded')::boolean
                       ELSE false
                   END AS degraded,
                   CASE
                       WHEN dt.normalized_context ->> 'signalSimulated' IN ('true', 'false')
                           THEN (dt.normalized_context ->> 'signalSimulated')::boolean
                       ELSE false
                   END AS simulated,
                   (dt.algorithm_version IS NOT NULL
                       AND dt.normalized_context ? 'decisionEngineVersion'
                       AND dt.normalized_context ? 'reasonCatalogVersion') AS provenance_available
              FROM audit.decision_trace dt
              JOIN protection.protection_request pr
                ON pr.id = dt.protection_request_id
             WHERE 1 = 1
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcDecisionInvestigationQuery(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public DecisionInvestigationPage search(DecisionInvestigationCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");

        StringBuilder sql = new StringBuilder(BASE_QUERY);
        MapSqlParameterSource parameters = new MapSqlParameterSource();

        addTextFilter(sql, parameters, "dt.correlation_id", "correlationId", criteria.correlationId());
        addTextFilter(sql, parameters, "pr.event_type", "eventType", criteria.eventType());
        addTextFilter(sql, parameters, "dt.outcome", "outcome", criteria.outcome());
        addTextFilter(sql, parameters, "dt.policy_version", "policyVersion", criteria.policyVersion());
        addRiskBandFilter(sql, criteria.riskBand());

        if (criteria.decidedFrom() != null) {
            sql.append(" AND dt.decided_at >= :decidedFrom\n");
            parameters.addValue("decidedFrom", Timestamp.from(criteria.decidedFrom()));
        }
        if (criteria.decidedTo() != null) {
            sql.append(" AND dt.decided_at < :decidedTo\n");
            parameters.addValue("decidedTo", Timestamp.from(criteria.decidedTo()));
        }

        DecisionCursor cursor = criteria.cursor() == null ? null : DecisionCursor.decode(criteria.cursor());
        if (cursor != null) {
            sql.append("""
                     AND (dt.decided_at < :cursorDecidedAt
                          OR (dt.decided_at = :cursorDecidedAt AND dt.id < :cursorDecisionId))
                    """);
            parameters.addValue("cursorDecidedAt", Timestamp.from(cursor.decidedAt()));
            parameters.addValue("cursorDecisionId", cursor.decisionId());
        }

        sql.append(" ORDER BY dt.decided_at DESC, dt.id DESC LIMIT :limit");
        parameters.addValue("limit", criteria.pageSize() + 1);

        List<DecisionInvestigationSummary> fetched = jdbcTemplate.query(
                sql.toString(), parameters, this::mapSummary);
        boolean hasMore = fetched.size() > criteria.pageSize();
        List<DecisionInvestigationSummary> decisions = hasMore
                ? new ArrayList<>(fetched.subList(0, criteria.pageSize()))
                : fetched;
        String nextCursor = hasMore && !decisions.isEmpty()
                ? DecisionCursor.from(decisions.get(decisions.size() - 1)).encode()
                : null;

        return new DecisionInvestigationPage(decisions, nextCursor, criteria.pageSize(), hasMore);
    }

    private void addTextFilter(
            StringBuilder sql,
            MapSqlParameterSource parameters,
            String column,
            String parameter,
            String value) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = :").append(parameter).append('\n');
            parameters.addValue(parameter, value);
        }
    }

    private void addRiskBandFilter(StringBuilder sql, String riskBand) {
        if (riskBand == null) {
            return;
        }
        switch (riskBand) {
            case "LOW" -> sql.append(" AND dt.risk_score < 30\n");
            case "MEDIUM" -> sql.append(" AND dt.risk_score BETWEEN 30 AND 69\n");
            case "HIGH" -> sql.append(" AND dt.risk_score >= 70\n");
            default -> throw new IllegalArgumentException("unsupported riskBand");
        }
    }

    private DecisionInvestigationSummary mapSummary(ResultSet rs, int rowNumber) throws SQLException {
        int riskScore = rs.getInt("risk_score");
        return new DecisionInvestigationSummary(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("correlation_id"),
                rs.getString("event_type"),
                rs.getString("outcome"),
                riskScore,
                riskBand(riskScore),
                rs.getString("policy_key"),
                rs.getString("policy_version"),
                rs.getTimestamp("decided_at").toInstant(),
                rs.getBoolean("degraded"),
                rs.getBoolean("simulated"),
                rs.getBoolean("provenance_available"));
    }

    private String riskBand(int score) {
        if (score >= 70) {
            return "HIGH";
        }
        if (score >= 30) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private record DecisionCursor(Instant decidedAt, UUID decisionId) {

        private static DecisionCursor from(DecisionInvestigationSummary summary) {
            return new DecisionCursor(summary.decidedAt(), UUID.fromString(summary.decisionReference()));
        }

        private String encode() {
            String raw = decidedAt + "|" + decisionId;
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        private static DecisionCursor decode(String value) {
            try {
                String raw = new String(
                        Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
                String[] parts = raw.split("\\|", -1);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("invalid decision search cursor");
                }
                return new DecisionCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("invalid decision search cursor");
            }
        }
    }
}
