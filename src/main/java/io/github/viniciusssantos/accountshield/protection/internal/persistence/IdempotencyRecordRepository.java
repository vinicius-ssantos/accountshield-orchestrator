package io.github.viniciusssantos.accountshield.protection.internal.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, UUID> {

    Optional<IdempotencyRecordEntity> findByClientIdAndIdempotencyKey(String clientId, String idempotencyKey);

    /**
     * Atomically claims (client_id, idempotency_key) if no row owns it yet. Returns 1 if this
     * call won the claim, 0 if a row already exists (either a completed prior request or a
     * concurrent racer — Postgres blocks this insert until any in-flight conflicting transaction
     * resolves, so a 0 here always reflects a fully-committed row).
     */
    @Modifying
    @Query(value = """
            INSERT INTO protection.idempotency_record
                (id, client_id, idempotency_key, request_fingerprint, resource_type, resource_id,
                 response_payload, created_at, expires_at)
            VALUES (:id, :clientId, :idempotencyKey, :fingerprint, :resourceType, :resourceId,
                    NULL, :createdAt, :expiresAt)
            ON CONFLICT (client_id, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("clientId") String clientId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("fingerprint") String fingerprint,
            @Param("resourceType") String resourceType,
            @Param("resourceId") UUID resourceId,
            @Param("createdAt") Instant createdAt,
            @Param("expiresAt") Instant expiresAt);

    @Modifying
    @Query("UPDATE IdempotencyRecordEntity e SET e.responsePayload = :responsePayload "
            + "WHERE e.clientId = :clientId AND e.idempotencyKey = :idempotencyKey")
    void updateResponsePayload(
            @Param("clientId") String clientId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("responsePayload") String responsePayload);

    void deleteByClientIdAndIdempotencyKey(String clientId, String idempotencyKey);

    @Modifying
    @Query(value = """
            DELETE FROM protection.idempotency_record
            WHERE id IN (
                SELECT id FROM protection.idempotency_record
                WHERE expires_at < :cutoff
                ORDER BY expires_at
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteExpiredBatch(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
