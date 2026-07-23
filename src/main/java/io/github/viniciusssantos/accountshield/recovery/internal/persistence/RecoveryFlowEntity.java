package io.github.viniciusssantos.accountshield.recovery.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recovery_flow", schema = "recovery")
public class RecoveryFlowEntity {

    @Id
    private UUID id;

    @Column(name = "account_reference", nullable = false, length = 128)
    private String accountReference;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "classification", nullable = false, length = 24)
    private String classification;

    @Column(name = "identity_challenge_id")
    private UUID identityChallengeId;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Column(name = "initiated_at", nullable = false)
    private Instant initiatedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "eligible_after")
    private Instant eligibleAfter;

    @Column(name = "reviewer", length = 128)
    private String reviewer;

    @Column(name = "protection_request_id")
    private UUID protectionRequestId;

    protected RecoveryFlowEntity() {
    }

    public RecoveryFlowEntity(
            UUID id,
            String accountReference,
            String eventType,
            String status,
            String classification,
            UUID identityChallengeId,
            int riskScore,
            Instant initiatedAt,
            Instant updatedAt,
            Instant eligibleAfter,
            String reviewer,
            UUID protectionRequestId) {
        this.id = id;
        this.accountReference = accountReference;
        this.eventType = eventType;
        this.status = status;
        this.classification = classification;
        this.identityChallengeId = identityChallengeId;
        this.riskScore = riskScore;
        this.initiatedAt = initiatedAt;
        this.updatedAt = updatedAt;
        this.eligibleAfter = eligibleAfter;
        this.reviewer = reviewer;
        this.protectionRequestId = protectionRequestId;
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

    public String getClassification() {
        return classification;
    }

    public void setClassification(String classification) {
        this.classification = classification;
    }

    public UUID getIdentityChallengeId() {
        return identityChallengeId;
    }

    public void setIdentityChallengeId(UUID identityChallengeId) {
        this.identityChallengeId = identityChallengeId;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public Instant getInitiatedAt() {
        return initiatedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getEligibleAfter() {
        return eligibleAfter;
    }

    public void setEligibleAfter(Instant eligibleAfter) {
        this.eligibleAfter = eligibleAfter;
    }

    public String getReviewer() {
        return reviewer;
    }

    public void setReviewer(String reviewer) {
        this.reviewer = reviewer;
    }

    public String getAccountReference() {
        return accountReference;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getProtectionRequestId() {
        return protectionRequestId;
    }
}
