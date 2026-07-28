package io.github.viniciusssantos.accountshield.crypto.internal;

import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the AES-256 key-encryption key (KEK) for a given version. Supports exactly one active
 * and, during a rotation window, one previous version -- enough to rotate keys without downtime:
 * historical subject keys wrapped under the previous version stay readable until {@link
 * SubjectKeyRewrapJob} re-wraps them onto the active version.
 *
 * <p>Each secret must be base64-encoded material that decodes to exactly 32 bytes (AES-256). A
 * passphrase run through a bare hash is not accepted: that pattern offers no resistance to offline
 * brute force against a human-chosen value. Operators generate a key with, e.g.,
 * {@code openssl rand -base64 32} (see {@code docs/RELEASING.md}).
 */
@Component
public class KeyEncryptionKeyResolver {

    static final int KEK_LENGTH_BYTES = 32;

    private final int activeVersion;
    private final SecretKeySpec activeKey;
    private final Integer previousVersion;
    private final SecretKeySpec previousKey;

    public KeyEncryptionKeyResolver(
            @Value("${accountshield.crypto.active-kek-version:1}") int activeVersion,
            @Value("${accountshield.crypto.active-kek-secret:fV2x6TR85adre0B8wtaHGnLekX6MOoPjm1du9h/MBKY=}")
            String activeSecret,
            @Value("${accountshield.crypto.previous-kek-version:0}") int previousVersion,
            @Value("${accountshield.crypto.previous-kek-secret:}") String previousSecret) {
        this.activeVersion = activeVersion;
        this.activeKey = decodeKek(activeSecret);
        if (previousVersion > 0 && previousSecret != null && !previousSecret.isBlank()) {
            this.previousVersion = previousVersion;
            this.previousKey = decodeKek(previousSecret);
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

    private static SecretKeySpec decodeKek(String base64Key) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "KEK secret must be base64-encoded 32-byte key material (AES-256); "
                            + "the provided value is not valid base64",
                    exception);
        }
        if (keyBytes.length != KEK_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "KEK secret must decode to exactly " + KEK_LENGTH_BYTES
                            + " bytes for AES-256, got " + keyBytes.length
                            + " -- generate one with, e.g., 'openssl rand -base64 32'");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
