package io.github.viniciusssantos.accountshield.policy.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

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

    @Column(name = "allow_max_score")
    private Short allowMaxScore;

    @Column(name = "step_up_max_score")
    private Short stepUpMaxScore;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    protected PolicyVersionEntity() {
    }

    public PolicyVersionEntity(
            UUID id,
            String policyKey,
            String version,
            String status,
            Instant createdAt,
            Instant activatedAt) {
        this(id, policyKey, version, status, null, null, createdAt, activatedAt);
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
        this.id = id;
        this.policyKey = policyKey;
        this.version = version;
        this.status = status;
        this.allowMaxScore = allowMaxScore;
        this.stepUpMaxScore = stepUpMaxScore;
        this.createdAt = createdAt;
        this.activatedAt = activatedAt;
    }

    public String getPolicyKey() {
        return policyKey;
    }

    public String getVersion() {
        return version;
    }

    public Short getAllowMaxScore() {
        return allowMaxScore;
    }

    public Short getStepUpMaxScore() {
        return stepUpMaxScore;
    }
}
