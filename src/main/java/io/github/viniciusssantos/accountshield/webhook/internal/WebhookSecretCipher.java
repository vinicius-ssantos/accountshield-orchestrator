package io.github.viniciusssantos.accountshield.webhook.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts webhook subscription secrets at rest under a single, static, app-level AES-256 key
 * (derived via SHA-256 from a configured passphrase, matching this codebase's existing
 * secret-configuration style). Deliberately not built on the {@code crypto} module's
 * per-subject envelope encryption (ADR 0025): that mechanism exists to make the same plaintext
 * always resolve to the same subject key, for crypto-shredding by identifier. A webhook secret
 * is an opaque, randomly generated value with no "identifier" of its own and no shredding
 * requirement (a disabled or rotated subscription's old secret simply stops being used) --
 * reusing that machinery here would borrow a data model built for a different problem.
 */
@Component
class WebhookSecretCipher {

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int TAG_LENGTH_BITS = 128;
    private static final int NONCE_LENGTH_BYTES = 12;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    WebhookSecretCipher(
            @Value("${accountshield.webhook.secret-encryption-key:accountshield-local-only-webhook-secret-key}")
            String secret) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(hash, "AES");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
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

    record EncryptedSecret(byte[] ciphertext, byte[] nonce) {
    }
}
