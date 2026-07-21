package io.github.viniciusssantos.accountshield.protection.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_record", schema = "protection")
public class IdempotencyRecordEntity {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, length = 128, unique = true)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "response_payload", columnDefinition = "jsonb")
    private String responsePayload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecordEntity() {
    }

    public IdempotencyRecordEntity(
            UUID id,
            String idempotencyKey,
            String requestFingerprint,
            String resourceType,
            UUID resourceId,
            String responsePayload,
            Instant createdAt,
            Instant expiresAt) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.responsePayload = responsePayload;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public String getResponsePayload() {
        return responsePayload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
