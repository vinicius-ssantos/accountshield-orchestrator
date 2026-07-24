package io.github.viniciusssantos.accountshield;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

// local/demo-only JWT issuer: keys are generated fresh per boot and never persisted or committed
@Configuration
public class LocalJwtKeys {

    public static final String ROLES_CLAIM = "roles";

    private final KeyPair keyPair = generateKeyPair();

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
    }

    public String signToken(String subject, List<String> roles, Duration ttl, Clock clock) {
        Instant now = clock.instant();
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .claim(ROLES_CLAIM, roles)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(ttl)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("unable to sign local JWT", exception);
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("RSA key generation is not available", exception);
        }
    }
}
