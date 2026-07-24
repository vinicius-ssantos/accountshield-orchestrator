package io.github.viniciusssantos.accountshield.recovery.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recovery_authorization", schema = "recovery")
public class RecoveryAuthorizationEntity {

    @Id
    private UUID id;

    @Column(name = "protection_request_id", nullable = false, unique = true)
    private UUID protectionRequestId;

    @Column(name = "decision_id", nullable = false, unique = true)
    private UUID decisionId;

    @Column(name = "account_reference", nullable = false, length = 128)
    private String accountReference;

    @Column(nullable = false, length = 32)
    private String directive;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected RecoveryAuthorizationEntity() {
    }

    public RecoveryAuthorizationEntity(
            UUID id,
            UUID protectionRequestId,
            UUID decisionId,
            String accountReference,
            String directive,
            int riskScore,
            Instant issuedAt,
            Instant expiresAt,
            Instant consumedAt) {
        this.id = id;
        this.protectionRequestId = protectionRequestId;
        this.decisionId = decisionId;
        this.accountReference = accountReference;
        this.directive = directive;
        this.riskScore = riskScore;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.consumedAt = consumedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProtectionRequestId() {
        return protectionRequestId;
    }

    public UUID getDecisionId() {
        return decisionId;
    }

    public String getAccountReference() {
        return accountReference;
    }

    public String getDirective() {
        return directive;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void consume(Instant instant) {
        if (consumedAt != null) {
            throw new IllegalStateException("recovery authorization is already consumed");
        }
        consumedAt = instant;
    }
}
