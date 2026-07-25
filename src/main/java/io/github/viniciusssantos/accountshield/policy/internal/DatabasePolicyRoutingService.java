package io.github.viniciusssantos.accountshield.policy.internal;

import io.github.viniciusssantos.accountshield.policy.ActivePolicyUnavailableException;
import io.github.viniciusssantos.accountshield.policy.PolicyRoutingService;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.ClientPolicyRouteEntity;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.ClientPolicyRouteRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DatabasePolicyRoutingService implements PolicyRoutingService {

    private final ClientPolicyRouteRepository repository;

    DatabasePolicyRoutingService(ClientPolicyRouteRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public String resolvePolicyKey(String clientId, String eventType) {
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        ClientPolicyRouteEntity route = repository.findByClientIdAndEventType(clientId, eventType)
                .orElseThrow(() -> new ActivePolicyUnavailableException(clientId + ":" + eventType));
        return route.getPolicyKey();
    }
}
