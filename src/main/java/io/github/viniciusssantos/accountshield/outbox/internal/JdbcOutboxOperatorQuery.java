package io.github.viniciusssantos.accountshield.outbox.internal;

import io.github.viniciusssantos.accountshield.outbox.OutboxOperatorQuery;
import io.github.viniciusssantos.accountshield.outbox.OutboxOperatorQuery.OutboxHealthSummary;
import io.github.viniciusssantos.accountshield.outbox.OutboxOperatorQuery.OutboxOperatorEventPage;
import io.github.viniciusssantos.accountshield.outbox.OutboxOperatorQuery.OutboxOperatorEventRecord;
import io.github.viniciusssantos.accountshield.outbox.OutboxOperatorQuery.OutboxOperatorSearchCriteria;
import io.github.viniciusssantos.accountshield.outbox.OutboxOperatorQuery.OutboxOperatorSearchResult;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcOutboxOperatorQuery implements OutboxOperatorQuery {

    private static final String HEALTH_QUERY = """
            SELECT
                COUNT(*) FILTER (WHERE status = 'PENDING' AND attempt_count = 0) AS pending_count,
                COUNT(*) FILTER (WHERE status = 'PENDING' AND attempt_count > 0) AS retrying_count,
                COUNT(*) FILTER (WHERE status = 'IN_PROGRESS') AS in_progress_count,
                COUNT(*) FILTER (WHERE status = 'DEAD_LETTERED') AS dead_lettered_count,
                COUNT(*) FILTER (WHERE status = 'DEAD_LETTERED' AND dead_lettered_at >= :windowStart)
                    AS recently_dead_lettered_count,
                COUNT(*) FILTER (WHERE status = 'PUBLISHED' AND published_at >= :windowStart)
                    AS recently_published_count,
                MIN(occurred_at) FILTER (WHERE status = 'PENDING') AS oldest_pending_occurred_at
              FROM outbox.outbox_event
            """;

    private static final String SEARCH_BASE_QUERY = """
            SELECT id, aggregate_type, aggregate_id, event_type, status, attempt_count,
                   occurred_at, published_at, dead_lettered_at, next_attempt_at, claimed_at,
                   last_error_category, payload::jsonb ->> 'schemaVersion' AS schema_version
              FROM outbox.outbox_event
             WHERE 1 = 1
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Clock clock;

    JdbcOutboxOperatorQuery(
            NamedParameterJdbcTemplate jdbcTemplate, @Qualifier("decisionClock") Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public OutboxOperatorSearchResult search(OutboxOperatorSearchCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        Instant now = clock.instant();
        return new OutboxOperatorSearchResult(health(now), events(criteria));
    }

    private OutboxHealthSummary health(Instant now) {
        Instant windowStart = now.minus(Duration.ofMinutes(WINDOW_MINUTES));
        MapSqlParameterSource parameters =
                new MapSqlParameterSource("windowStart", Timestamp.from(windowStart));
        return jdbcTemplate.queryForObject(HEALTH_QUERY, parameters, (rs, rowNumber) -> {
            Timestamp oldestPending = rs.getTimestamp("oldest_pending_occurred_at");
            Double oldestPendingAgeSeconds = oldestPending == null
                    ? null
                    : (double) Duration.between(oldestPending.toInstant(), now).toSeconds();
            return new OutboxHealthSummary(
                    rs.getLong("pending_count"),
                    rs.getLong("retrying_count"),
                    rs.getLong("in_progress_count"),
                    rs.getLong("dead_lettered_count"),
                    oldestPendingAgeSeconds,
                    rs.getLong("recently_dead_lettered_count"),
                    rs.getLong("recently_published_count"),
                    WINDOW_MINUTES,
                    now);
        });
    }

    private OutboxOperatorEventPage events(OutboxOperatorSearchCriteria criteria) {
        StringBuilder sql = new StringBuilder(SEARCH_BASE_QUERY);
        MapSqlParameterSource parameters = new MapSqlParameterSource();

        if (!criteria.statuses().isEmpty()) {
            sql.append(" AND status IN (:statuses)\n");
            parameters.addValue("statuses", criteria.statuses());
        }
        if (criteria.eventType() != null) {
            sql.append(" AND event_type = :eventType\n");
            parameters.addValue("eventType", criteria.eventType());
        }
        if (criteria.occurredFrom() != null) {
            sql.append(" AND occurred_at >= :occurredFrom\n");
            parameters.addValue("occurredFrom", Timestamp.from(criteria.occurredFrom()));
        }
        if (criteria.occurredTo() != null) {
            sql.append(" AND occurred_at < :occurredTo\n");
            parameters.addValue("occurredTo", Timestamp.from(criteria.occurredTo()));
        }
        if (criteria.minAttemptCount() != null) {
            sql.append(" AND attempt_count >= :minAttemptCount\n");
            parameters.addValue("minAttemptCount", criteria.minAttemptCount());
        }
        if (criteria.maxAttemptCount() != null) {
            sql.append(" AND attempt_count <= :maxAttemptCount\n");
            parameters.addValue("maxAttemptCount", criteria.maxAttemptCount());
        }

        OutboxCursor cursor = criteria.cursor() == null ? null : OutboxCursor.decode(criteria.cursor());
        if (cursor != null) {
            sql.append("""
                     AND (occurred_at < :cursorOccurredAt
                          OR (occurred_at = :cursorOccurredAt AND id < :cursorEventId))
                    """);
            parameters.addValue("cursorOccurredAt", Timestamp.from(cursor.occurredAt()));
            parameters.addValue("cursorEventId", cursor.eventId());
        }

        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT :limit");
        parameters.addValue("limit", criteria.pageSize() + 1);

        List<OutboxOperatorEventRecord> fetched = jdbcTemplate.query(sql.toString(), parameters, this::mapRecord);
        boolean hasMore = fetched.size() > criteria.pageSize();
        List<OutboxOperatorEventRecord> records = hasMore
                ? new ArrayList<>(fetched.subList(0, criteria.pageSize()))
                : fetched;
        String nextCursor = hasMore && !records.isEmpty()
                ? OutboxCursor.from(records.get(records.size() - 1)).encode()
                : null;

        return new OutboxOperatorEventPage(records, nextCursor, criteria.pageSize(), hasMore);
    }

    private OutboxOperatorEventRecord mapRecord(ResultSet rs, int rowNumber) throws SQLException {
        String status = rs.getString("status");
        Timestamp claimedAtTimestamp = rs.getTimestamp("claimed_at");
        Instant claimedAt = claimedAtTimestamp == null ? null : claimedAtTimestamp.toInstant();
        Timestamp nextAttemptAtTimestamp = rs.getTimestamp("next_attempt_at");
        // never a meaningful value once terminal -- the column is not cleared on those transitions
        Instant nextAttemptAt = ("PENDING".equals(status) || "IN_PROGRESS".equals(status))
                && nextAttemptAtTimestamp != null
                        ? nextAttemptAtTimestamp.toInstant()
                        : null;
        Timestamp publishedAtTimestamp = rs.getTimestamp("published_at");
        Timestamp deadLetteredAtTimestamp = rs.getTimestamp("dead_lettered_at");
        String failureCategory = rs.getString("last_error_category");

        return new OutboxOperatorEventRecord(
                rs.getObject("id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getString("event_type"),
                status,
                rs.getInt("attempt_count"),
                rs.getTimestamp("occurred_at").toInstant(),
                publishedAtTimestamp == null ? null : publishedAtTimestamp.toInstant(),
                deadLetteredAtTimestamp == null ? null : deadLetteredAtTimestamp.toInstant(),
                nextAttemptAt,
                claimedAt != null,
                claimedAt,
                rs.getString("schema_version"),
                mask(rs.getString("aggregate_id")),
                "DEAD_LETTERED".equals(status) && failureCategory != null,
                "DEAD_LETTERED".equals(status) ? failureCategory : null);
    }

    private static String mask(String reference) {
        return "••••" + reference.substring(Math.max(0, reference.length() - 4));
    }

    private record OutboxCursor(Instant occurredAt, UUID eventId) {

        private static OutboxCursor from(OutboxOperatorEventRecord record) {
            return new OutboxCursor(record.occurredAt(), record.eventId());
        }

        private String encode() {
            String raw = occurredAt + "|" + eventId;
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        private static OutboxCursor decode(String value) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
                String[] parts = raw.split("\\|", -1);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("invalid outbox search cursor");
                }
                return new OutboxCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("invalid outbox search cursor");
            }
        }
    }
}
