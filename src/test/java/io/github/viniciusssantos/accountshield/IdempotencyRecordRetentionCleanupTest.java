package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.protection.internal.IdempotencyRecordRetentionCleanup;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
@TestPropertySource(properties = "accountshield.protection.idempotency.retention.batch-size=2")
class IdempotencyRecordRetentionCleanupTest {

    @Autowired private IdempotencyRecordRetentionCleanup retentionCleanup;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void purgesOnlyExpiredRecordsAcrossMoreThanOneBatch() {
        UUID recent = insertRecord(OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
        UUID expired1 = insertRecord(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10));
        UUID expired2 = insertRecord(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(20));
        UUID expired3 = insertRecord(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30));
        UUID expired4 = insertRecord(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(40));
        UUID expired5 = insertRecord(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(50));

        retentionCleanup.purgeExpiredRecords();

        assertThat(recordExists(recent)).isTrue();
        assertThat(recordExists(expired1)).isFalse();
        assertThat(recordExists(expired2)).isFalse();
        assertThat(recordExists(expired3)).isFalse();
        assertThat(recordExists(expired4)).isFalse();
        assertThat(recordExists(expired5)).isFalse();
    }

    private boolean recordExists(UUID id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM protection.idempotency_record WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    private UUID insertRecord(OffsetDateTime expiresAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO protection.idempotency_record (
                    id, client_id, idempotency_key, request_fingerprint, resource_type, resource_id,
                    response_payload, created_at, expires_at
                ) VALUES (?, 'default-client', ?, ?, 'protection_decision', ?, '{}'::jsonb, ?, ?)
                """,
                id,
                "idem-retention-" + id,
                "0".repeat(64),
                UUID.randomUUID(),
                expiresAt.minusHours(24),
                expiresAt);
        return id;
    }
}
