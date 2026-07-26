package io.github.viniciusssantos.accountshield.crypto.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the AES-256 key-encryption key (KEK) for a given version. Supports exactly one active
 * and, during a rotation window, one previous version -- enough to rotate keys without downtime:
 * historical subject keys wrapped under the previous version stay readable until {@link
 * SubjectKeyRewrapJob} re-wraps them onto the active version.
 */
@Component
public class KeyEncryptionKeyResolver {

    private final int activeVersion;
    private final SecretKeySpec activeKey;
    private final Integer previousVersion;
    private final SecretKeySpec previousKey;

    public KeyEncryptionKeyResolver(
            @Value("${accountshield.crypto.active-kek-version:1}") int activeVersion,
            @Value("${accountshield.crypto.active-kek-secret:accountshield-local-only-kek-v1}")
            String activeSecret,
            @Value("${accountshield.crypto.previous-kek-version:0}") int previousVersion,
            @Value("${accountshield.crypto.previous-kek-secret:}") String previousSecret) {
        this.activeVersion = activeVersion;
        this.activeKey = deriveKey(activeSecret);
        if (previousVersion > 0 && previousSecret != null && !previousSecret.isBlank()) {
            this.previousVersion = previousVersion;
            this.previousKey = deriveKey(previousSecret);
        } else {
            this.previousVersion = null;
            this.previousKey = null;
        }
    }

    public int activeVersion() {
        return activeVersion;
    }

    public SecretKeySpec keyForVersion(int version) {
        if (version == activeVersion) {
            return activeKey;
        }
        if (previousVersion != null && version == previousVersion) {
            return previousKey;
        }
        throw new IllegalStateException("no key-encryption key configured for version " + version);
    }

    private static SecretKeySpec deriveKey(String secret) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, "AES");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
