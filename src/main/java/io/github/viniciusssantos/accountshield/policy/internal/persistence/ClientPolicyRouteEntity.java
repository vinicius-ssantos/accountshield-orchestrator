package io.github.viniciusssantos.accountshield.policy.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "client_policy_route", schema = "policy")
public class ClientPolicyRouteEntity {

    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "policy_key", nullable = false, length = 100)
    private String policyKey;

    protected ClientPolicyRouteEntity() {
    }

    public ClientPolicyRouteEntity(UUID id, String clientId, String eventType, String policyKey) {
        this.id = id;
        this.clientId = clientId;
        this.eventType = eventType;
        this.policyKey = policyKey;
    }

    public UUID getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPolicyKey() {
        return policyKey;
    }
}
