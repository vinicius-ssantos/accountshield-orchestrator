package io.github.viniciusssantos.accountshield.recovery.internal;

import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowSearchQuery;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowSearchQuery.RecoveryFlowSearchCriteria;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowSearchQuery.RecoveryFlowSearchPage;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowSearchQuery.RecoveryFlowSearchSummary;
import io.github.viniciusssantos.accountshield.recovery.RecoveryStatus;
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
public class JdbcRecoveryFlowSearchQuery implements RecoveryFlowSearchQuery {

    private static final String BASE_QUERY = """
            SELECT id,
                   account_reference,
                   event_type,
                   status,
                   classification,
                   classification_rule_version,
                   risk_score,
                   initiated_at,
                   updated_at,
                   eligible_after
              FROM recovery.recovery_flow
             WHERE 1 = 1
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcRecoveryFlowSearchQuery(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public RecoveryFlowSearchPage search(RecoveryFlowSearchCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");

        StringBuilder sql = new StringBuilder(BASE_QUERY);
        MapSqlParameterSource parameters = new MapSqlParameterSource();

        addTextFilter(sql, parameters, "status", "status", criteria.status());
        addTextFilter(sql, parameters, "classification", "classification", criteria.classification());
        addTextFilter(sql, parameters, "event_type", "eventType", criteria.eventType());

        if (criteria.initiatedFrom() != null) {
            sql.append(" AND initiated_at >= :initiatedFrom\n");
            parameters.addValue("initiatedFrom", Timestamp.from(criteria.initiatedFrom()));
        }
        if (criteria.initiatedTo() != null) {
            sql.append(" AND initiated_at < :initiatedTo\n");
            parameters.addValue("initiatedTo", Timestamp.from(criteria.initiatedTo()));
        }
        if (criteria.eligibleAfter() != null) {
            sql.append(" AND eligible_after IS NOT NULL AND eligible_after >= :eligibleAfterFilter\n");
            parameters.addValue("eligibleAfterFilter", Timestamp.from(criteria.eligibleAfter()));
        }
        if (criteria.eligibleBefore() != null) {
            sql.append(" AND eligible_after IS NOT NULL AND eligible_after < :eligibleBeforeFilter\n");
            parameters.addValue("eligibleBeforeFilter", Timestamp.from(criteria.eligibleBefore()));
        }
        if (criteria.minimumRiskScore() != null) {
            sql.append(" AND risk_score >= :minimumRiskScore\n");
            parameters.addValue("minimumRiskScore", criteria.minimumRiskScore());
        }
        if (criteria.maximumRiskScore() != null) {
            sql.append(" AND risk_score <= :maximumRiskScore\n");
            parameters.addValue("maximumRiskScore", criteria.maximumRiskScore());
        }

        RecoveryCursor cursor = criteria.cursor() == null ? null : RecoveryCursor.decode(criteria.cursor());
        if (cursor != null) {
            sql.append("""
                     AND (updated_at < :cursorUpdatedAt
                          OR (updated_at = :cursorUpdatedAt AND id < :cursorRecoveryId))
                    """);
            parameters.addValue("cursorUpdatedAt", Timestamp.from(cursor.updatedAt()));
            parameters.addValue("cursorRecoveryId", cursor.recoveryId());
        }

        sql.append(" ORDER BY updated_at DESC, id DESC LIMIT :limit");
        parameters.addValue("limit", criteria.pageSize() + 1);

        List<RecoveryFlowSearchSummary> fetched = jdbcTemplate.query(sql.toString(), parameters, this::mapSummary);
        boolean hasMore = fetched.size() > criteria.pageSize();
        List<RecoveryFlowSearchSummary> recoveries = hasMore
                ? new ArrayList<>(fetched.subList(0, criteria.pageSize()))
                : fetched;
        String nextCursor = hasMore && !recoveries.isEmpty()
                ? RecoveryCursor.from(recoveries.get(recoveries.size() - 1)).encode()
                : null;

        return new RecoveryFlowSearchPage(recoveries, nextCursor, criteria.pageSize(), hasMore);
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

    private RecoveryFlowSearchSummary mapSummary(ResultSet rs, int rowNumber) throws SQLException {
        String status = rs.getString("status");
        Instant eligibleAfter = rs.getTimestamp("eligible_after") == null
                ? null
                : rs.getTimestamp("eligible_after").toInstant();
        return new RecoveryFlowSearchSummary(
                rs.getObject("id", UUID.class).toString(),
                maskSubject(rs.getString("account_reference")),
                rs.getString("event_type"),
                status,
                RecoveryStatus.valueOf(status).isTerminal(),
                rs.getString("classification"),
                rs.getString("classification_rule_version"),
                rs.getInt("risk_score"),
                rs.getTimestamp("initiated_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                eligibleAfter);
    }

    private String maskSubject(String accountReference) {
        if (accountReference == null || accountReference.isBlank()) {
            return "masked-subject";
        }
        int suffixLength = Math.min(4, accountReference.length());
        return "••••" + accountReference.substring(accountReference.length() - suffixLength);
    }

    private record RecoveryCursor(Instant updatedAt, UUID recoveryId) {

        private static RecoveryCursor from(RecoveryFlowSearchSummary summary) {
            return new RecoveryCursor(summary.updatedAt(), UUID.fromString(summary.recoveryReference()));
        }

        private String encode() {
            String raw = updatedAt + "|" + recoveryId;
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        private static RecoveryCursor decode(String value) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
                String[] parts = raw.split("\\|", -1);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("invalid recovery search cursor");
                }
                return new RecoveryCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("invalid recovery search cursor");
            }
        }
    }
}
