package io.github.viniciusssantos.accountshield;

import io.github.viniciusssantos.accountshield.DemoOperatorCredentialVerifier.VerifiedOperator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Demo/portfolio-scoped operator login -- see ADR 0046. Verifies against a small, fixed set of
// config-seeded personas (DemoOperatorCredentialVerifier) and, on success, issues a JWT via the
// same LocalJwtKeys signer /dev/tokens already uses. This is not a real identity provider.
@RestController
@RequestMapping("/auth/session-tokens")
public class DemoOperatorSessionController {

    private static final Duration TOKEN_TTL = Duration.ofMinutes(15);

    private final DemoOperatorCredentialVerifier verifier;
    private final LocalJwtKeys localJwtKeys;
    private final Clock clock;

    public DemoOperatorSessionController(
            DemoOperatorCredentialVerifier verifier,
            LocalJwtKeys localJwtKeys,
            @Qualifier("decisionClock") Clock clock) {
        this.verifier = verifier;
        this.localJwtKeys = localJwtKeys;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<TokenResponse> issue(@Valid @RequestBody CredentialsRequest request) {
        VerifiedOperator operator = verifier
                .verify(request.username(), request.password())
                .orElseThrow(InvalidCredentialsException::new);
        return tokenResponse(operator.subject(), operator.roles());
    }

    // Reached only once the oauth2 resource server filter chain has already validated the
    // caller's current bearer token (signature + not-yet-expired) exactly as it does for every
    // other authenticated endpoint -- no bespoke token parsing here.
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        List<String> roles = jwt.getClaimAsStringList(LocalJwtKeys.ROLES_CLAIM);
        return tokenResponse(jwt.getSubject(), roles);
    }

    private ResponseEntity<TokenResponse> tokenResponse(String subject, List<String> roles) {
        String token = localJwtKeys.signToken(subject, roles, TOKEN_TTL, clock);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new TokenResponse(token, clock.instant().plus(TOKEN_TTL)));
    }

    public record CredentialsRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record TokenResponse(String token, Instant expiresAt) {
    }
}
