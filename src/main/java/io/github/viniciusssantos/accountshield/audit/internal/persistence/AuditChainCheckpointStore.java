package io.github.viniciusssantos.accountshield.audit.internal.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AuditChainCheckpointStore {

    private static final String SELECT_CHECKPOINT =
            "SELECT last_verified_sequence FROM audit.chain_verification_checkpoint WHERE id = 1";
    private static final String UPDATE_CHECKPOINT =
            "UPDATE audit.chain_verification_checkpoint SET last_verified_sequence = ?, updated_at = ? WHERE id = 1";

    private final JdbcTemplate jdbcTemplate;

    public AuditChainCheckpointStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long lastVerifiedSequence() {
        Long value = jdbcTemplate.queryForObject(SELECT_CHECKPOINT, Long.class);
        return value == null ? 0L : value;
    }

    public void advanceTo(long sequence, Instant now) {
        jdbcTemplate.update(UPDATE_CHECKPOINT, sequence, Timestamp.from(now));
    }
}
