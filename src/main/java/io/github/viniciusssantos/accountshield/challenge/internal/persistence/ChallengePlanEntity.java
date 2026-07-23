package io.github.viniciusssantos.accountshield.challenge.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "challenge_plan", schema = "challenge")
public class ChallengePlanEntity {

    @Id
    private UUID id;

    @Column(name = "account_reference", nullable = false, length = 128)
    private String accountReference;

    @Column(name = "challenge_type", nullable = false, length = 32)
    private String challengeType;

    @Column(name = "purpose", nullable = false, length = 32)
    private String purpose;

    @Column(name = "context_id", nullable = false)
    private UUID contextId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "max_attempts", nullable = false)
    private short maxAttempts;

    @Column(name = "remaining_attempts", nullable = false)
    private short remainingAttempts;

    @Column(name = "expected_code", nullable = false, length = 64)
    private String expectedCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected ChallengePlanEntity() {
    }

    public ChallengePlanEntity(
            UUID id,
            String accountReference,
            String challengeType,
            String purpose,
            UUID contextId,
            String status,
            short maxAttempts,
            short remainingAttempts,
            String expectedCode,
            Instant createdAt,
            Instant expiresAt,
            Instant consumedAt) {
        this.id = id;
        this.accountReference = accountReference;
        this.challengeType = challengeType;
        this.purpose = purpose;
        this.contextId = contextId;
        this.status = status;
        this.maxAttempts = maxAttempts;
        this.remainingAttempts = remainingAttempts;
        this.expectedCode = expectedCode;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.consumedAt = consumedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public short getRemainingAttempts() {
        return remainingAttempts;
    }

    public void setRemainingAttempts(short remainingAttempts) {
        this.remainingAttempts = remainingAttempts;
    }

    public short getMaxAttempts() {
        return maxAttempts;
    }

    public String getExpectedCode() {
        return expectedCode;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getAccountReference() {
        return accountReference;
    }

    public String getChallengeType() {
        return challengeType;
    }

    public String getPurpose() {
        return purpose;
    }

    public UUID getContextId() {
        return contextId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(Instant consumedAt) {
        this.consumedAt = consumedAt;
    }

    public long getVersion() {
        return version;
    }
}
