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
        this.id = id;
        this.policyKey = policyKey;
        this.version = version;
        this.status = status;
        this.createdAt = createdAt;
        this.activatedAt = activatedAt;
    }
}
