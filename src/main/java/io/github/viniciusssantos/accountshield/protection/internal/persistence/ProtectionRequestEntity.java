package io.github.viniciusssantos.accountshield.protection.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "protection_request", schema = "protection")
public class ProtectionRequestEntity {

    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Convert(converter = AccountReferenceEncryptionConverter.class)
    @Column(name = "account_reference", nullable = false, length = 512)
    private String accountReference;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    protected ProtectionRequestEntity() {
    }

    public ProtectionRequestEntity(
            UUID id,
            String clientId,
            String accountReference,
            String eventType,
            String requestFingerprint,
            String status,
            Instant requestedAt) {
        this.id = id;
        this.clientId = clientId;
        this.accountReference = accountReference;
        this.eventType = eventType;
        this.requestFingerprint = requestFingerprint;
        this.status = status;
        this.requestedAt = requestedAt;
    }
}
