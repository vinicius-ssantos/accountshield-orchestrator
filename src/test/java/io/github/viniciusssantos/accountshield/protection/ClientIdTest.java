package io.github.viniciusssantos.accountshield.protection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ClientIdTest {

    @Test
    void acceptsAValidValue() {
        ClientId clientId = new ClientId("acme-corp");

        assertThat(clientId.value()).isEqualTo("acme-corp");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new ClientId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> new ClientId("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTooLong() {
        assertThatThrownBy(() -> new ClientId("a".repeat(101))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultConstantRepresentsExistingSingleTenantTraffic() {
        assertThat(ClientId.DEFAULT.value()).isEqualTo("default-client");
    }
}
