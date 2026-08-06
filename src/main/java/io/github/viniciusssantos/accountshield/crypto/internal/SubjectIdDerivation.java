package io.github.viniciusssantos.accountshield.crypto.internal;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deterministic, one-way subject identifier derived from a plaintext value, used as the primary
 * key of {@code crypto.subject_key}. Independent of {@code outbox.AccountPseudonymizer} (a
 * separate secret, a separate purpose) so the crypto module has no dependency on the outbox
 * module's internals.
 */
@Component
class SubjectIdDerivation {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    SubjectIdDerivation(
            @Value("${accountshield.crypto.subject-id-secret:accountshield-local-only-subject-id-secret}")
            String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    byte[] deriveRaw(String plaintext) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("unable to derive subject id", exception);
        }
    }

    String deriveHex(String plaintext) {
        return HexFormat.of().formatHex(deriveRaw(plaintext));
    }
}
