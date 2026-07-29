package io.github.viniciusssantos.accountshield.audit.internal.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AuditChainCheckpointStore {

    /**
     * Arbitrary fixed key (distinct from {@code JdbcDecisionTraceRecorder}'s
     * {@code CHAIN_LOCK_KEY}) scoping a Postgres advisory transaction lock to "read-verify-advance
     * the audit chain integrity checkpoint" (issue #150 / F-22). Held for the duration of the
     * caller's transaction, serializing concurrent scheduled-job ticks so they cannot compute
     * overlapping verification ranges from the same stale {@link #lastVerifiedSequence()} read.
     */
    private static final long CHECKPOINT_LOCK_KEY = 4417002983561042L;

    private static final String LOCK_CHECKPOINT = "SELECT pg_advisory_xact_lock(?)";
    private static final String SELECT_CHECKPOINT =
            "SELECT last_verified_sequence FROM audit.chain_verification_checkpoint WHERE id = 1";

    // GREATEST() makes the write monotonic even without the lock above (issue #150 / F-22):
    // without it, two interleaved read-verify-advance cycles could commit in an order that
    // overwrites a higher already-committed sequence with a lower one, regressing the
    // "forward-only" checkpoint guarantee ADR 0027 describes.
    private static final String UPDATE_CHECKPOINT = """
            UPDATE audit.chain_verification_checkpoint
            SET last_verified_sequence = GREATEST(last_verified_sequence, ?), updated_at = ?
            WHERE id = 1
            """;

    private final JdbcTemplate jdbcTemplate;

    public AuditChainCheckpointStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Must be called within the same transaction as the {@link #lastVerifiedSequence()} read it guards. */
    public void acquireVerificationLock() {
        jdbcTemplate.queryForList(LOCK_CHECKPOINT, CHECKPOINT_LOCK_KEY);
    }

    public long lastVerifiedSequence() {
        Long value = jdbcTemplate.queryForObject(SELECT_CHECKPOINT, Long.class);
        return value == null ? 0L : value;
    }

    public void advanceTo(long sequence, Instant now) {
        jdbcTemplate.update(UPDATE_CHECKPOINT, sequence, Timestamp.from(now));
    }
}
