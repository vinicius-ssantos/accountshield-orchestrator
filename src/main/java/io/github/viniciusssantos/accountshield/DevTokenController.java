package io.github.viniciusssantos.accountshield;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dev/tokens")
@Profile("local")
class DevTokenController {

    private static final Set<String> KNOWN_ROLES = Set.of(
            "PROTECTION_CLIENT", "SECURITY_OPERATOR", "POLICY_ADMIN",
            "SIMULATION_ANALYST", "OBSERVABILITY_READER");
    private static final Duration TOKEN_TTL = Duration.ofHours(1);

    private final LocalJwtKeys localJwtKeys;
    private final Clock clock;

    DevTokenController(LocalJwtKeys localJwtKeys, @Qualifier("decisionClock") Clock clock) {
        this.localJwtKeys = localJwtKeys;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<TokenResponse> issue(@Valid @RequestBody TokenRequest request) {
        for (String role : request.roles()) {
            if (!KNOWN_ROLES.contains(role)) {
                throw new IllegalArgumentException("unknown role: " + role);
            }
        }
        String token = localJwtKeys.signToken(request.subject(), request.roles(), TOKEN_TTL, clock);
        return ResponseEntity.ok(new TokenResponse(token));
    }

    record TokenRequest(@NotBlank String subject, @NotEmpty List<String> roles) {
    }

    record TokenResponse(String token) {
    }
}
