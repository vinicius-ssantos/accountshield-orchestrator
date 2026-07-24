package io.github.viniciusssantos.accountshield.challenge.internal;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class HmacChallengeCodeHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    HmacChallengeCodeHasher(
            @Value("${accountshield.challenge.hmac-secret:accountshield-local-only-challenge-secret}")
            String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    String hash(String code) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("unable to hash challenge code", exception);
        }
    }

    boolean matches(String providedCode, String storedHashHex) {
        byte[] computed = hash(providedCode).getBytes(StandardCharsets.UTF_8);
        byte[] stored = storedHashHex.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(computed, stored);
    }
}
