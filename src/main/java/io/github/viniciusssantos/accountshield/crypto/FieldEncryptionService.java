package io.github.viniciusssantos.accountshield.crypto;

/**
 * Envelope encryption for individual sensitive field values, keyed by a per-subject data
 * encryption key (DEK) wrapped by a versioned key-encryption key (KEK). Destroying a subject's
 * wrapped DEK ({@link #shred(String)}) makes every value ever encrypted under it permanently
 * irrecoverable, without touching the rows that store the ciphertext.
 */
public interface FieldEncryptionService {

    /**
     * Returned by {@link #decrypt(String)} in place of the original plaintext once the owning
     * subject key has been destroyed via {@link #shred(String)}.
     */
    String SHREDDED_MARKER = "[[CRYPTO_SHREDDED]]";

    /**
     * Encrypts {@code plaintext} under the active key-encryption key version, creating the
     * subject's data encryption key on first use. Column values written before this feature
     * existed, and values decrypted without ever being re-encrypted, are plain strings; this
     * method always produces the versioned encrypted representation.
     */
    String encrypt(String plaintext);

    /**
     * Decrypts a value previously produced by {@link #encrypt(String)}. A value that is not in
     * the encrypted representation (for example a legacy plaintext row written before this
     * feature existed) is returned unchanged. A value whose subject key has been destroyed
     * returns {@link #SHREDDED_MARKER} rather than throwing, so bulk reads over historical data
     * remain resilient to individually shredded subjects.
     */
    String decrypt(String storedValue);

    /**
     * Permanently destroys the data encryption key for the subject identified by {@code
     * plaintext}. Idempotent: shredding an already-shredded or never-encrypted subject is a
     * no-op. After this call, every value previously encrypted for this subject is irrecoverable,
     * and any future {@link #encrypt(String)} call for the same plaintext fails with {@link
     * SubjectKeyDestroyedException}.
     */
    void shred(String plaintext);
}
