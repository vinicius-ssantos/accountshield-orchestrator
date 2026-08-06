package io.github.viniciusssantos.accountshield.webhook.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "webhook_subscription", schema = "webhook")
public class WebhookSubscriptionEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(name = "event_type_filter", length = 160)
    private String eventTypeFilter;

    @Column(name = "secret_ciphertext", nullable = false)
    private byte[] secretCiphertext;

    @Column(name = "secret_nonce", nullable = false)
    private byte[] secretNonce;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "secret_rotated_at")
    private Instant secretRotatedAt;

    protected WebhookSubscriptionEntity() {
    }

    public WebhookSubscriptionEntity(
            UUID id,
            String url,
            String eventTypeFilter,
            byte[] secretCiphertext,
            byte[] secretNonce,
            Instant createdAt) {
        this.id = id;
        this.url = url;
        this.eventTypeFilter = eventTypeFilter;
        this.secretCiphertext = secretCiphertext;
        this.secretNonce = secretNonce;
        this.status = "ACTIVE";
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    public String getEventTypeFilter() {
        return eventTypeFilter;
    }

    public byte[] getSecretCiphertext() {
        return secretCiphertext;
    }

    public byte[] getSecretNonce() {
        return secretNonce;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSecretRotatedAt() {
        return secretRotatedAt;
    }

    public boolean matchesEventType(String eventType) {
        return eventTypeFilter == null || eventTypeFilter.equals(eventType);
    }

    public void rotateSecret(byte[] newCiphertext, byte[] newNonce, Instant now) {
        this.secretCiphertext = Objects.requireNonNull(newCiphertext, "newCiphertext must not be null");
        this.secretNonce = Objects.requireNonNull(newNonce, "newNonce must not be null");
        this.secretRotatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void setEnabled(boolean enabled) {
        this.status = enabled ? "ACTIVE" : "DISABLED";
    }
}
