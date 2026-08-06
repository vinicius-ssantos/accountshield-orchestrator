package io.github.viniciusssantos.accountshield.recovery.internal;

import io.github.viniciusssantos.accountshield.challenge.ChallengeInvestigationQuery;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.ChallengeEvidence;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.RecoveryCriteria;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.RecoveryDetail;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.RecoveryPage;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.RecoverySummary;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.SectionAvailability;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JdbcRecoveryOperationsQuery implements RecoveryOperationsQuery {

    private static final Set<String> TERMINAL_STATUSES = Set.of(
            RecoveryStatus.COMPLETED.name(),
            RecoveryStatus.IDENTITY_FAILED.name(),
            RecoveryStatus.REJECTED.name(),
            RecoveryStatus.ABORTED.name());

    private static final String BASE_QUERY = """
            SELECT rf.id,
                   rf.account_reference,
                   rf.event_type,
                   rf.status,
                   rf.classification,
                   rf.classification_rule_version,
                   rf.identity_challenge_id,
                   rf.risk_score,
                   rf.initiated_at,
                   rf.updated_at,
                   rf.eligible_after,
                   rf.reviewer,
                   rf.protection_request_id,
                   rf.originating_decision_id
              FROM recovery.recovery_flow rf
             WHERE 1 = 1
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ChallengeInvestigationQuery challengeQuery;

    public JdbcRecoveryOperationsQuery(
            NamedParameterJdbcTemplate jdbcTemplate,
            ChallengeInvestigationQuery challengeQuery) {
        this.jdbcTemplate = jdbcTemplate;
        this.challengeQuery = challengeQuery;
    }

    @Override
    @Transactional(readOnly = true)
    public RecoveryPage search(RecoveryCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        StringBuilder sql = new StringBuilder(BASE_QUERY);
        MapSqlParameterSource parameters = new MapSqlParameterSource();

        addTextFilter(sql, parameters, "rf.status", "status", criteria.status());
        addTextFilter(sql, parameters, "rf.classification", "classification", criteria.classification());
        addTextFilter(sql, parameters, "rf.event_type", "eventType", criteria.eventType());
        addInstantFilter(sql, parameters, "rf.initiated_at", ">=", "initiatedFrom", criteria.initiatedFrom());
        addInstantFilter(sql, parameters, "rf.initiated_at", "<", "initiatedTo", criteria.initiatedTo());
        addInstantFilter(sql, parameters, "rf.eligible_after", ">=", "eligibleFrom", criteria.eligibleFrom());
        addInstantFilter(sql, parameters, "rf.eligible_after", "<", "eligibleTo", criteria.eligibleTo());
        addIntegerFilter(sql, parameters, "rf.risk_score", ">=", "minimumRiskScore", criteria.minimumRiskScore());
        addIntegerFilter(sql, parameters, "rf.risk_score", "<=", "maximumRiskScore", criteria.maximumRiskScore());
        addReviewStateFilter(sql, criteria.reviewState());

        RecoveryCursor cursor = criteria.cursor() == null ? null : RecoveryCursor.decode(criteria.cursor());
        if (cursor != null) {
            sql.append("""
                     AND (rf.updated_at < :cursorUpdatedAt
                          OR (rf.updated_at = :cursorUpdatedAt AND rf.id < :cursorRecoveryId))
                    """);
            parameters.addValue("cursorUpdatedAt", Timestamp.from(cursor.updatedAt()));
            parameters.addValue("cursorRecoveryId", cursor.recoveryId());
        }

        sql.append(" ORDER BY rf.updated_at DESC, rf.id DESC LIMIT :limit");
        parameters.addValue("limit", criteria.pageSize() + 1);

        List<RecoverySummary> fetched = jdbcTemplate.query(sql.toString(), parameters, this::mapSummary);
        boolean hasMore = fetched.size() > criteria.pageSize();
        List<RecoverySummary> recoveries = hasMore
                ? new ArrayList<>(fetched.subList(0, criteria.pageSize()))
                : fetched;
        String nextCursor = hasMore && !recoveries.isEmpty()
                ? RecoveryCursor.from(recoveries.getLast()).encode()
                : null;
        return new RecoveryPage(recoveries, nextCursor, criteria.pageSize(), hasMore);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RecoveryDetail> investigate(String recoveryReference) {
        UUID recoveryId = parseRecoveryReference(recoveryReference);
        String sql = BASE_QUERY + " AND rf.id = :recoveryId";
        List<DetailRow> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("recoveryId", recoveryId),
                this::mapDetailRow);
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        DetailRow row = rows.getFirst();
        List<ChallengeEvidence> challenges = row.identityChallengeId() == null
                ? List.of()
                : challengeQuery.findByContextId(recoveryId).stream()
                        .filter(challenge -> row.identityChallengeId().equals(challenge.reference()))
                        .map(challenge -> new ChallengeEvidence(
                                challenge.reference().toString(),
                                challenge.challengeType(),
                                challenge.purpose(),
                                challenge.status(),
                                challenge.createdAt(),
                                challenge.expiresAt(),
                                challenge.consumedAt()))
                        .toList();

        SectionAvailability challengeAvailability;
        if (row.identityChallengeId() == null) {
            challengeAvailability = SectionAvailability.NOT_APPLICABLE;
        } else if (challenges.isEmpty()) {
            challengeAvailability = SectionAvailability.UNAVAILABLE;
        } else {
            challengeAvailability = SectionAvailability.AVAILABLE;
        }

        return Optional.of(new RecoveryDetail(
                toSummary(row),
                row.protectionRequestId().toString(),
                row.reviewer() != null,
                challenges,
                challengeAvailability,
                challengeAvailability == SectionAvailability.UNAVAILABLE));
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

    private void addInstantFilter(
            StringBuilder sql,
            MapSqlParameterSource parameters,
            String column,
            String operator,
            String parameter,
            Instant value) {
        if (value != null) {
            sql.append(" AND ")
                    .append(column)
                    .append(' ')
                    .append(operator)
                    .append(" :")
                    .append(parameter)
                    .append('\n');
            parameters.addValue(parameter, Timestamp.from(value));
        }
    }

    private void addIntegerFilter(
            StringBuilder sql,
            MapSqlParameterSource parameters,
            String column,
            String operator,
            String parameter,
            Integer value) {
        if (value != null) {
            sql.append(" AND ")
                    .append(column)
                    .append(' ')
                    .append(operator)
                    .append(" :")
                    .append(parameter)
                    .append('\n');
            parameters.addValue(parameter, value);
        }
    }

    private void addReviewStateFilter(StringBuilder sql, String reviewState) {
        if (reviewState == null) {
            return;
        }
        switch (reviewState) {
            case "PENDING" -> sql.append(
                    " AND rf.status = 'MANUAL_REVIEW' AND rf.reviewer IS NULL\n");
            case "REVIEWED" -> sql.append(" AND rf.reviewer IS NOT NULL\n");
            case "NOT_APPLICABLE" -> sql.append(
                    " AND rf.status <> 'MANUAL_REVIEW' AND rf.reviewer IS NULL\n");
            default -> throw new IllegalArgumentException("unsupported reviewState");
        }
    }

    private RecoverySummary mapSummary(ResultSet resultSet, int rowNumber) throws SQLException {
        return toSummary(mapDetailRow(resultSet, rowNumber));
    }

    private DetailRow mapDetailRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DetailRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("account_reference"),
                resultSet.getString("event_type"),
                resultSet.getString("status"),
                resultSet.getString("classification"),
                resultSet.getString("classification_rule_version"),
                resultSet.getObject("identity_challenge_id", UUID.class),
                resultSet.getInt("risk_score"),
                resultSet.getTimestamp("initiated_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant(),
                nullableInstant(resultSet, "eligible_after"),
                resultSet.getString("reviewer"),
                resultSet.getObject("protection_request_id", UUID.class),
                resultSet.getObject("originating_decision_id", UUID.class));
    }

    private RecoverySummary toSummary(DetailRow row) {
        return new RecoverySummary(
                row.recoveryId().toString(),
                maskSubject(row.accountReference()),
                row.eventType(),
                row.status(),
                TERMINAL_STATUSES.contains(row.status()),
                row.classification(),
                row.classificationRuleVersion(),
                row.riskScore(),
                row.initiatedAt(),
                row.updatedAt(),
                row.eligibleAfter(),
                row.originatingDecisionId().toString(),
                reviewState(row),
                row.identityChallengeId() != null);
    }

    private Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String reviewState(DetailRow row) {
        if (row.reviewer() != null) {
            return "REVIEWED";
        }
        if (RecoveryStatus.MANUAL_REVIEW.name().equals(row.status())) {
            return "PENDING";
        }
        return "NOT_APPLICABLE";
    }

    private String maskSubject(String accountReference) {
        if (accountReference == null || accountReference.isBlank()) {
            return "masked-subject";
        }
        int suffixLength = Math.min(4, accountReference.length());
        return "••••" + accountReference.substring(accountReference.length() - suffixLength);
    }

    private UUID parseRecoveryReference(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("recoveryReference must be a valid UUID");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("recoveryReference must be a valid UUID");
        }
    }

    private record DetailRow(
            UUID recoveryId,
            String accountReference,
            String eventType,
            String status,
            String classification,
            String classificationRuleVersion,
            UUID identityChallengeId,
            int riskScore,
            Instant initiatedAt,
            Instant updatedAt,
            Instant eligibleAfter,
            String reviewer,
            UUID protectionRequestId,
            UUID originatingDecisionId) {
    }

    private record RecoveryCursor(Instant updatedAt, UUID recoveryId) {

        private static RecoveryCursor from(RecoverySummary summary) {
            return new RecoveryCursor(
                    summary.updatedAt(), UUID.fromString(summary.recoveryReference()));
        }

        private String encode() {
            String raw = updatedAt + "|" + recoveryId;
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        private static RecoveryCursor decode(String value) {
            try {
                String raw = new String(
                        Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
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
