package io.github.viniciusssantos.accountshield.audit.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "decision_trace", schema = "audit")
public class DecisionTraceEntity {

    @Id
    private UUID id;

    @Column(name = "protection_request_id", nullable = false, unique = true)
    private UUID protectionRequestId;

    @Column(name = "account_reference", nullable = false, length = 128)
    private String accountReference;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "algorithm_version", nullable = false, length = 40)
    private String algorithmVersion;

    @Column(name = "policy_key", nullable = false, length = 100)
    private String policyKey;

    @Column(name = "policy_version", nullable = false, length = 40)
    private String policyVersion;

    @Column(nullable = false, length = 32)
    private String outcome;

    @Column(name = "risk_score", nullable = false)
    private short riskScore;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    protected DecisionTraceEntity() {
    }

    public DecisionTraceEntity(
            UUID id,
            UUID protectionRequestId,
            String accountReference,
            String requestFingerprint,
            String algorithmVersion,
            String policyKey,
            String policyVersion,
            String outcome,
            short riskScore,
            Instant decidedAt) {
        this.id = id;
        this.protectionRequestId = protectionRequestId;
        this.accountReference = accountReference;
        this.requestFingerprint = requestFingerprint;
        this.algorithmVersion = algorithmVersion;
        this.policyKey = policyKey;
        this.policyVersion = policyVersion;
        this.outcome = outcome;
        this.riskScore = riskScore;
        this.decidedAt = decidedAt;
    }
}
