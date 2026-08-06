package io.github.viniciusssantos.accountshield.outbox.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Atomic claim-and-transition operations over {@code outbox.outbox_event}, deliberately bypassing
 * JPA/optimistic-locking for this table: {@code FOR UPDATE SKIP LOCKED} at the row level is what
 * makes concurrent claiming safe across relay instances, not the entity's {@code @Version} column.
 *
 * <p>Every successful claim (including reclaiming an abandoned {@code IN_PROGRESS} row past the
 * claim timeout -- see {@link #CLAIM_BATCH}) is issued a fresh random {@code claim_token}. Every
 * acknowledgement ({@link #markPublished}, {@link #markFailedWithBackoff}, {@link
 * #markDeadLettered}) requires that exact token and {@code status = 'IN_PROGRESS'}; a stale
 * worker whose claim was already reclaimed by a newer owner therefore affects zero rows instead
 * of overwriting state the new owner already wrote (issue #145 / F-18).
 */
@Component
class OutboxClaimStore {

    private static final String CLAIM_BATCH = """
            WITH claimed AS (
                UPDATE outbox.outbox_event
                   SET status = 'IN_PROGRESS', claimed_at = ?, claimed_by = ?, claim_token = gen_random_uuid()
                 WHERE id IN (
                     SELECT id FROM outbox.outbox_event
                      WHERE (status = 'PENDING' AND next_attempt_at <= ?)
                         OR (status = 'IN_PROGRESS' AND claimed_at < ?)
                      ORDER BY occurred_at ASC
                      LIMIT ?
                        FOR UPDATE SKIP LOCKED
                 )
                RETURNING id, aggregate_type, aggregate_id, event_type, payload::text AS payload_text,
                          occurred_at, attempt_count, claim_token
            )
            SELECT * FROM claimed ORDER BY occurred_at ASC
            """;

    private static final String MARK_PUBLISHED = """
            UPDATE outbox.outbox_event
               SET status = 'PUBLISHED', published_at = ?
             WHERE id = ? AND status = 'IN_PROGRESS' AND claim_token = ?
            """;

    private static final String MARK_FAILED_WITH_BACKOFF = """
            UPDATE outbox.outbox_event
               SET status = 'PENDING', attempt_count = ?, last_error = ?, last_error_category = ?,
                   next_attempt_at = ?, claimed_at = NULL, claimed_by = NULL, claim_token = NULL
             WHERE id = ? AND status = 'IN_PROGRESS' AND claim_token = ?
            """;

    private static final String MARK_DEAD_LETTERED = """
            UPDATE outbox.outbox_event
               SET status = 'DEAD_LETTERED', attempt_count = ?, last_error = ?, last_error_category = ?,
                   dead_lettered_at = ?
             WHERE id = ? AND status = 'IN_PROGRESS' AND claim_token = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    OutboxClaimStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<ClaimedOutboxEvent> claimBatch(Instant now, Instant staleClaimCutoff, String instanceId, int batchSize) {
        return jdbcTemplate.query(
                CLAIM_BATCH,
                (rs, rowNum) -> new ClaimedOutboxEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getString("event_type"),
                        rs.getString("payload_text"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getInt("attempt_count"),
                        rs.getObject("claim_token", UUID.class)),
                Timestamp.from(now), instanceId, Timestamp.from(now), Timestamp.from(staleClaimCutoff), batchSize);
    }

    /** @return {@code true} if this was still the current claim owner, {@code false} if the ack was stale (a no-op). */
    boolean markPublished(UUID id, UUID claimToken, Instant now) {
        int rowsAffected = jdbcTemplate.update(MARK_PUBLISHED, Timestamp.from(now), id, claimToken);
        return rowsAffected > 0;
    }

    /** @return {@code true} if this was still the current claim owner, {@code false} if the ack was stale (a no-op). */
    boolean markFailedWithBackoff(
            UUID id, UUID claimToken, int newAttemptCount, String error, String errorCategory, Instant nextAttemptAt) {
        int rowsAffected = jdbcTemplate.update(
                MARK_FAILED_WITH_BACKOFF, newAttemptCount, error, errorCategory,
                Timestamp.from(nextAttemptAt), id, claimToken);
        return rowsAffected > 0;
    }

    /** @return {@code true} if this was still the current claim owner, {@code false} if the ack was stale (a no-op). */
    boolean markDeadLettered(UUID id, UUID claimToken, int newAttemptCount, String error, String errorCategory, Instant now) {
        int rowsAffected = jdbcTemplate.update(
                MARK_DEAD_LETTERED, newAttemptCount, error, errorCategory, Timestamp.from(now), id, claimToken);
        return rowsAffected > 0;
    }
}
