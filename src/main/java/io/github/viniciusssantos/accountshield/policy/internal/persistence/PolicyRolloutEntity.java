package io.github.viniciusssantos.accountshield.policy.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "policy_rollout", schema = "policy")
public class PolicyRolloutEntity {

    @Id
    private UUID id;

    @Column(name = "policy_key", nullable = false, length = 100)
    private String policyKey;

    @Column(name = "candidate_version", nullable = false, length = 40)
    private String candidateVersion;

    @Column(name = "rollout_percentage", nullable = false)
    private short rolloutPercentage;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "started_by", nullable = false, length = 200)
    private String startedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "rolled_back_at")
    private Instant rolledBackAt;

    @Column(name = "rolled_back_by", length = 200)
    private String rolledBackBy;

    protected PolicyRolloutEntity() {
    }

    public PolicyRolloutEntity(
            UUID id,
            String policyKey,
            String candidateVersion,
            short rolloutPercentage,
            String status,
            Instant startedAt,
            String startedBy,
            Instant updatedAt) {
        this.id = id;
        this.policyKey = policyKey;
        this.candidateVersion = candidateVersion;
        this.rolloutPercentage = rolloutPercentage;
        this.status = status;
        this.startedAt = startedAt;
        this.startedBy = startedBy;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getPolicyKey() {
        return policyKey;
    }

    public String getCandidateVersion() {
        return candidateVersion;
    }

    public short getRolloutPercentage() {
        return rolloutPercentage;
    }

    public String getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public String getStartedBy() {
        return startedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getRolledBackAt() {
        return rolledBackAt;
    }

    public String getRolledBackBy() {
        return rolledBackBy;
    }

    public void updatePercentage(short newPercentage, Instant now) {
        this.rolloutPercentage = newPercentage;
        this.updatedAt = now;
    }

    public void rollback(String actor, Instant now) {
        this.status = "ROLLED_BACK";
        this.rolledBackBy = actor;
        this.rolledBackAt = now;
        this.updatedAt = now;
    }
}
