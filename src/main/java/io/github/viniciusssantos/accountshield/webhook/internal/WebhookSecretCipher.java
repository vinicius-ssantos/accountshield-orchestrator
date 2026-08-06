package io.github.viniciusssantos.accountshield.webhook.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts webhook subscription secrets at rest under a single, static, app-level AES-256 key
 * (base64-encoded 32-byte key material, validated at construction). Deliberately not built on the
 * {@code crypto} module's per-subject envelope encryption (ADR 0025): that mechanism exists to make
 * the same plaintext always resolve to the same subject key, for crypto-shredding by identifier. A
 * webhook secret is an opaque, randomly generated value with no "identifier" of its own and no
 * shredding requirement (a disabled or rotated subscription's old secret simply stops being used)
 * -- reusing that machinery here would borrow a data model built for a different problem.
 */
@Component
class WebhookSecretCipher {

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int TAG_LENGTH_BITS = 128;
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int KEY_LENGTH_BYTES = 32;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    WebhookSecretCipher(
            @Value("${accountshield.webhook.secret-encryption-key:MQK2zVpJuhFHt9iIhP2WkFZC0rW80SVg5vz9SStRMxQ=}")
            String secret) {
        this.key = decodeAesKey(secret);
    }

    EncryptedSecret encrypt(String plaintext) {
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        secureRandom.nextBytes(nonce);
        byte[] ciphertext = run(Cipher.ENCRYPT_MODE, nonce, plaintext.getBytes(StandardCharsets.UTF_8));
        return new EncryptedSecret(ciphertext, nonce);
    }

    String decrypt(byte[] ciphertext, byte[] nonce) {
        return new String(run(Cipher.DECRYPT_MODE, nonce, ciphertext), StandardCharsets.UTF_8);
    }

    private byte[] run(int mode, byte[] nonce, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(mode, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES-GCM operation failed", exception);
        }
    }

    private static SecretKeySpec decodeAesKey(String base64Key) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "webhook secret-encryption-key must be base64-encoded 32-byte key material (AES-256); "
                            + "the provided value is not valid base64",
                    exception);
        }
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "webhook secret-encryption-key must decode to exactly " + KEY_LENGTH_BYTES
                            + " bytes for AES-256, got " + keyBytes.length
                            + " -- generate one with, e.g., 'openssl rand -base64 32'");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    record EncryptedSecret(byte[] ciphertext, byte[] nonce) {
    }
}
