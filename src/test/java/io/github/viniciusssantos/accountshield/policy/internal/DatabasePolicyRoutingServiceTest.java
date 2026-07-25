package io.github.viniciusssantos.accountshield.policy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.policy.ActivePolicyUnavailableException;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.ClientPolicyRouteEntity;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.ClientPolicyRouteRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DatabasePolicyRoutingServiceTest {

    private final ClientPolicyRouteRepository repository = mock(ClientPolicyRouteRepository.class);
    private final DatabasePolicyRoutingService service = new DatabasePolicyRoutingService(repository);

    @Test
    void resolvesTheSeededDefaultMapping() {
        when(repository.findByClientIdAndEventType("default-client", "LOGIN_ATTEMPT"))
                .thenReturn(Optional.of(new ClientPolicyRouteEntity(
                        UUID.randomUUID(), "default-client", "LOGIN_ATTEMPT", "account-protection-default")));

        String policyKey = service.resolvePolicyKey("default-client", "LOGIN_ATTEMPT");

        assertThat(policyKey).isEqualTo("account-protection-default");
    }

    @Test
    void routesDifferentClientsToDifferentPolicyKeys() {
        when(repository.findByClientIdAndEventType("acme-corp", "LOGIN_ATTEMPT"))
                .thenReturn(Optional.of(new ClientPolicyRouteEntity(
                        UUID.randomUUID(), "acme-corp", "LOGIN_ATTEMPT", "acme-login-policy")));

        String policyKey = service.resolvePolicyKey("acme-corp", "LOGIN_ATTEMPT");

        assertThat(policyKey).isEqualTo("acme-login-policy");
    }

    @Test
    void throwsActivePolicyUnavailableWhenNoRouteExists() {
        when(repository.findByClientIdAndEventType("unknown-client", "LOGIN_ATTEMPT"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolvePolicyKey("unknown-client", "LOGIN_ATTEMPT"))
                .isInstanceOf(ActivePolicyUnavailableException.class);
    }

    @Test
    void rejectsNullInputs() {
        assertThatThrownBy(() -> service.resolvePolicyKey(null, "LOGIN_ATTEMPT"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.resolvePolicyKey("default-client", null))
                .isInstanceOf(NullPointerException.class);
    }
}
