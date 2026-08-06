package io.github.viniciusssantos.accountshield;

import io.github.viniciusssantos.accountshield.DemoOperatorCredentialProperties.Persona;
import java.util.List;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
class DemoOperatorCredentialVerifier {

    // Never matches any real password. Comparing against this whenever the supplied username
    // does not match a configured persona keeps verify() running one bcrypt comparison on every
    // call, so a failed login's cost does not reveal whether the username exists.
    private static final String DUMMY_HASH = "$2a$10$x5T3QgmRPNLmBMUED9Q8B.RyzU4XZDrfYHHJX1WWezihL4D/fA8PS";

    private final DemoOperatorCredentialProperties properties;
    private final PasswordEncoder passwordEncoder;

    DemoOperatorCredentialVerifier(DemoOperatorCredentialProperties properties, PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
    }

    Optional<VerifiedOperator> verify(String username, String password) {
        if (!properties.isEnabled()) {
            passwordEncoder.matches(password, DUMMY_HASH);
            return Optional.empty();
        }

        Persona persona = properties.getPersonas().stream()
                .filter(candidate -> candidate.username().equals(username))
                .findFirst()
                .orElse(null);

        boolean matches = passwordEncoder.matches(password, persona != null ? persona.bcryptHash() : DUMMY_HASH);
        if (persona == null || !matches) {
            return Optional.empty();
        }
        return Optional.of(new VerifiedOperator(persona.username(), persona.roles()));
    }

    record VerifiedOperator(String subject, List<String> roles) {
    }
}
