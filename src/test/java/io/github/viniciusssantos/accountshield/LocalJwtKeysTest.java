package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class LocalJwtKeysTest {

    private final LocalJwtKeys localJwtKeys = new LocalJwtKeys();

    @Test
    void signedTokenDecodesWithExpectedClaims() {
        JwtDecoder decoder = localJwtKeys.jwtDecoder();
        String token = localJwtKeys.signToken(
                "operator-alice", List.of("SECURITY_OPERATOR"), Duration.ofMinutes(5), Clock.systemUTC());

        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getSubject()).isEqualTo("operator-alice");
        assertThat(jwt.getClaimAsStringList(LocalJwtKeys.ROLES_CLAIM)).containsExactly("SECURITY_OPERATOR");
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
    }
}
