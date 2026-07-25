package io.github.viniciusssantos.accountshield.policy.internal.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientPolicyRouteRepository extends JpaRepository<ClientPolicyRouteEntity, UUID> {

    Optional<ClientPolicyRouteEntity> findByClientIdAndEventType(String clientId, String eventType);
}
