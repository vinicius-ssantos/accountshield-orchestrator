package io.github.viniciusssantos.accountshield;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// config-seeded, fixed demo operator personas backing POST /auth/session-tokens -- intentionally
// NOT a general user-management system (see ADR 0046); the bcrypt hashes here are of publicly
// documented demo passwords, not real secrets.
@Component
@ConfigurationProperties(prefix = "accountshield.auth.demo-credentials")
public class DemoOperatorCredentialProperties {

    private boolean enabled = true;
    private List<Persona> personas = List.of();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<Persona> getPersonas() {
        return personas;
    }

    public void setPersonas(List<Persona> personas) {
        this.personas = personas;
    }

    public record Persona(String username, String bcryptHash, List<String> roles) {
    }
}
