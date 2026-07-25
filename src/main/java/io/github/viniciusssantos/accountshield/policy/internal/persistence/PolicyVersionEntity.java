package io.github.viniciusssantos.accountshield.policy.internal.persistence;

import io.github.viniciusssantos.accountshield.policy.IllegalPolicyTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "policy_version", schema = "policy")
public class PolicyVersionEntity {

    @Id
    private UUID id;

    @Column(name = "policy_key", nullable = false, length = 100)
    private String policyKey;

    @Column(nullable = false, length = 40)
    private String version;

    @Column(nullable = false, length = 24)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition", nullable = false, columnDefinition = "jsonb")
    private String definition;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "analysis", columnDefinition = "jsonb")
    private String analysis;

    @Column(name = "allow_max_score")
    private Short allowMaxScore;

    @Column(name = "step_up_max_score")
    private Short stepUpMaxScore;

    @Column(name = "recovery_max_score")
    private Short recoveryMaxScore;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "created_by", length = 200)
    private String createdBy;

    @Column(name = "validated_by", length = 200)
    private String validatedBy;

    @Column(name = "validated_at")
    private Instant validatedAt;

    @Column(name = "approved_by", length = 200)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approval_reason", length = 500)
    private String approvalReason;

    protected PolicyVersionEntity() {
    }

    public PolicyVersionEntity(
            UUID id,
            String policyKey,
            String version,
            String status,
            Instant createdAt,
            Instant activatedAt) {
        this(id, policyKey, version, status, null, null, null, null, createdAt, activatedAt);
    }

    public PolicyVersionEntity(
            UUID id,
            String policyKey,
            String version,
            String status,
            Short allowMaxScore,
            Short stepUpMaxScore,
            Instant createdAt,
            Instant activatedAt) {
        this(id, policyKey, version, status, null, allowMaxScore, stepUpMaxScore, null,
                createdAt, activatedAt);
    }

    public PolicyVersionEntity(
            UUID id,
            String policyKey,
            String version,
            String status,
            Short allowMaxScore,
            Short stepUpMaxScore,
            Short recoveryMaxScore,
            Instant createdAt,
            Instant activatedAt) {
        this(id, policyKey, version, status, null, allowMaxScore, stepUpMaxScore,
                recoveryMaxScore, createdAt, activatedAt);
    }

    public PolicyVersionEntity(
            UUID id,
            String policyKey,
            String version,
            String status,
            String definition,
            Short allowMaxScore,
            Short stepUpMaxScore,
            Instant createdAt,
            Instant activatedAt) {
        this(id, policyKey, version, status, definition, allowMaxScore, stepUpMaxScore,
                null, createdAt, activatedAt);
    }

    public PolicyVersionEntity(
            UUID id,
            String policyKey,
            String version,
            String status,
            String definition,
            Short allowMaxScore,
            Short stepUpMaxScore,
            Short recoveryMaxScore,
            Instant createdAt,
            Instant activatedAt) {
        this.id = id;
        this.policyKey = policyKey;
        this.version = version;
        this.status = status;
        this.definition = definition;
        this.allowMaxScore = allowMaxScore;
        this.stepUpMaxScore = stepUpMaxScore;
        this.recoveryMaxScore = recoveryMaxScore;
        this.createdAt = createdAt;
        this.activatedAt = activatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getPolicyKey() {
        return policyKey;
    }

    public String getVersion() {
        return version;
    }

    public String getStatus() {
        return status;
    }

    public Short getAllowMaxScore() {
        return allowMaxScore;
    }

    public Short getStepUpMaxScore() {
        return stepUpMaxScore;
    }

    public Short getRecoveryMaxScore() {
        return recoveryMaxScore;
    }

    public String getAnalysis() {
        return analysis;
    }

    public void setAnalysis(String analysis) {
        this.analysis = analysis;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getValidatedBy() {
        return validatedBy;
    }

    public Instant getValidatedAt() {
        return validatedAt;
    }

    public void recordValidation(String validatedBy, Instant validatedAt) {
        this.validatedBy = validatedBy;
        this.validatedAt = validatedAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public String getApprovalReason() {
        return approvalReason;
    }

    public void recordApproval(String approvedBy, Instant approvedAt, String approvalReason) {
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.approvalReason = approvalReason;
    }

    public void transitionTo(String targetStatus, Instant now) {
        validateTransition(targetStatus);
        this.status = targetStatus;
        if ("ACTIVE".equals(targetStatus)) {
            this.activatedAt = now;
        }
    }

    private void validateTransition(String targetStatus) {
        String current = this.status;
        boolean allowed = switch (current) {
            case "DRAFT" -> "VALIDATED".equals(targetStatus) || "REJECTED".equals(targetStatus);
            case "VALIDATED" -> "APPROVED".equals(targetStatus) || "REJECTED".equals(targetStatus);
            case "APPROVED" -> "ACTIVE".equals(targetStatus) || "REJECTED".equals(targetStatus);
            case "ACTIVE" -> "RETIRED".equals(targetStatus);
            default -> false;
        };
        if (!allowed) {
            throw new IllegalPolicyTransitionException(policyKey, version, current, targetStatus);
        }
    }
}
