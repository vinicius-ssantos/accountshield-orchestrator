package io.github.viniciusssantos.accountshield.outbox.internal.persistence;

import io.github.viniciusssantos.accountshield.outbox.OutboxEventNotDeadLetteredException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_event", schema = "outbox")
public class OutboxEventEntity {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 80)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 128)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 160)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "last_error_category", length = 200)
    private String lastErrorCategory;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "claimed_by", length = 100)
    private String claimedBy;

    @Column(name = "dead_lettered_at")
    private Instant deadLetteredAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected OutboxEventEntity() {
    }

    public OutboxEventEntity(
            UUID id,
            String aggregateType,
            String aggregateId,
            String eventType,
            String payload,
            Instant occurredAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.attemptCount = 0;
        this.status = "PENDING";
        this.nextAttemptAt = occurredAt;
        this.version = 0;
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getLastError() {
        return lastError;
    }

    public String getLastErrorCategory() {
        return lastErrorCategory;
    }

    public String getStatus() {
        return status;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public Instant getDeadLetteredAt() {
        return deadLetteredAt;
    }

    public void markPublished(Instant publishedAt) {
        this.publishedAt = Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        this.status = "PUBLISHED";
    }

    public void recordFailure(String error, Instant now) {
        this.attemptCount++;
        this.lastError = Objects.requireNonNull(error, "error must not be null");
    }

    public void markDeadLettered(String error, Instant now) {
        this.status = "DEAD_LETTERED";
        this.lastError = Objects.requireNonNull(error, "error must not be null");
        this.deadLetteredAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void requeue(Instant now) {
        if (!"DEAD_LETTERED".equals(status)) {
            throw new OutboxEventNotDeadLetteredException(id, status);
        }
        this.status = "PENDING";
        this.nextAttemptAt = Objects.requireNonNull(now, "now must not be null");
        this.attemptCount = 0;
        this.lastError = null;
        this.lastErrorCategory = null;
        this.claimedAt = null;
        this.claimedBy = null;
        this.deadLetteredAt = null;
    }
}
