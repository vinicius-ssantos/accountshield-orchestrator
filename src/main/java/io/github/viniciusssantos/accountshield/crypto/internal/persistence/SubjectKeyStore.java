package io.github.viniciusssantos.accountshield.crypto.internal.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Plain JDBC access to {@code crypto.subject_key}, deliberately not a Spring Data JPA repository.
 * {@link io.github.viniciusssantos.accountshield.protection.internal.persistence.AccountReferenceEncryptionConverter}
 * is resolved by Hibernate while the {@code EntityManagerFactory} bean itself is still being
 * built; if this store depended on a JPA repository (which in turn depends on the
 * {@code EntityManagerFactory}), that dependency chain would be circular. {@code JdbcTemplate}
 * has no such dependency, so it breaks the cycle.
 */
@Component
public class SubjectKeyStore {

    private final JdbcTemplate jdbcTemplate;

    public SubjectKeyStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<SubjectKeyRecord> findById(String subjectId) {
        return jdbcTemplate.query(
                        """
                        SELECT subject_id, wrapped_dek, dek_nonce, kek_version, created_at, rewrapped_at, destroyed_at
                        FROM crypto.subject_key WHERE subject_id = ?
                        """,
                        SubjectKeyStore::mapRow, subjectId)
                .stream()
                .findFirst();
    }

    public void insert(String subjectId, byte[] wrappedDek, byte[] dekNonce, int kekVersion, Instant createdAt) {
        jdbcTemplate.update(
                """
                INSERT INTO crypto.subject_key (subject_id, wrapped_dek, dek_nonce, kek_version, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                subjectId, wrappedDek, dekNonce, kekVersion, Timestamp.from(createdAt));
    }

    public void rewrap(String subjectId, byte[] newWrappedDek, byte[] newNonce, int newKekVersion, Instant now) {
        jdbcTemplate.update(
                """
                UPDATE crypto.subject_key
                SET wrapped_dek = ?, dek_nonce = ?, kek_version = ?, rewrapped_at = ?
                WHERE subject_id = ?
                """,
                newWrappedDek, newNonce, newKekVersion, Timestamp.from(now), subjectId);
    }

    /**
     * Permanently destroys the subject's key material. Idempotent: a row already destroyed, or
     * never created, is left as-is (zero rows affected).
     */
    public void destroy(String subjectId, Instant now) {
        jdbcTemplate.update(
                """
                UPDATE crypto.subject_key
                SET wrapped_dek = NULL, dek_nonce = NULL, kek_version = NULL, destroyed_at = ?
                WHERE subject_id = ? AND destroyed_at IS NULL
                """,
                Timestamp.from(now), subjectId);
    }

    public List<SubjectKeyRecord> findBatchNeedingRewrap(int activeKekVersion, int batchSize) {
        return jdbcTemplate.query(
                """
                SELECT subject_id, wrapped_dek, dek_nonce, kek_version, created_at, rewrapped_at, destroyed_at
                FROM crypto.subject_key
                WHERE kek_version <> ? AND destroyed_at IS NULL
                ORDER BY created_at
                LIMIT ?
                """,
                SubjectKeyStore::mapRow, activeKekVersion, batchSize);
    }

    public long countNeedingRewrap(int activeKekVersion) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crypto.subject_key WHERE kek_version <> ? AND destroyed_at IS NULL",
                Long.class, activeKekVersion);
        return count == null ? 0 : count;
    }

    private static SubjectKeyRecord mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp rewrappedAt = resultSet.getTimestamp("rewrapped_at");
        Timestamp destroyedAt = resultSet.getTimestamp("destroyed_at");
        return new SubjectKeyRecord(
                resultSet.getString("subject_id"),
                resultSet.getBytes("wrapped_dek"),
                resultSet.getBytes("dek_nonce"),
                (Integer) resultSet.getObject("kek_version"),
                resultSet.getTimestamp("created_at").toInstant(),
                rewrappedAt == null ? null : rewrappedAt.toInstant(),
                destroyedAt == null ? null : destroyedAt.toInstant());
    }
}
