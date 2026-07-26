package io.github.viniciusssantos.accountshield.crypto.internal;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class AesGcmCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int TAG_LENGTH_BITS = 128;
    static final int NONCE_LENGTH_BYTES = 12;

    private AesGcmCipher() {
    }

    static byte[] encrypt(SecretKeySpec key, byte[] nonce, byte[] plaintext) {
        return run(Cipher.ENCRYPT_MODE, key, nonce, plaintext);
    }

    static byte[] decrypt(SecretKeySpec key, byte[] nonce, byte[] ciphertext) {
        return run(Cipher.DECRYPT_MODE, key, nonce, ciphertext);
    }

    static byte[] randomNonce(SecureRandom random) {
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        random.nextBytes(nonce);
        return nonce;
    }

    private static byte[] run(int mode, SecretKeySpec key, byte[] nonce, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(mode, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES-GCM operation failed", exception);
        }
    }
}
