package io.github.viniciusssantos.accountshield.crypto.internal;

import io.github.viniciusssantos.accountshield.crypto.FieldEncryptionService;
import io.github.viniciusssantos.accountshield.crypto.SubjectKeyDestroyedException;
import io.github.viniciusssantos.accountshield.crypto.internal.persistence.SubjectKeyRecord;
import io.github.viniciusssantos.accountshield.crypto.internal.persistence.SubjectKeyStore;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AesGcmFieldEncryptionService implements FieldEncryptionService {

    private static final String ENC_PREFIX = "ENC:";
    private static final int SUBJECT_ID_LENGTH_BYTES = 32;
    private static final int DEK_LENGTH_BYTES = 32;

    private final SubjectKeyStore subjectKeyStore;
    private final SubjectIdDerivation subjectIdDerivation;
    private final KeyEncryptionKeyResolver kekResolver;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmFieldEncryptionService(
            SubjectKeyStore subjectKeyStore,
            SubjectIdDerivation subjectIdDerivation,
            KeyEncryptionKeyResolver kekResolver,
            @Qualifier("decisionClock") Clock clock) {
        this.subjectKeyStore = subjectKeyStore;
        this.subjectIdDerivation = subjectIdDerivation;
        this.kekResolver = kekResolver;
        this.clock = clock;
    }

    @Override
    @Transactional
    public String encrypt(String plaintext) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        byte[] subjectIdBytes = subjectIdDerivation.deriveRaw(plaintext);
        String subjectId = HexFormat.of().formatHex(subjectIdBytes);

        byte[] dek = resolveDekForEncryption(subjectId);

        byte[] nonce = AesGcmCipher.randomNonce(secureRandom);
        byte[] ciphertext = AesGcmCipher.encrypt(
                new SecretKeySpec(dek, "AES"), nonce, plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] payload = concat(subjectIdBytes, nonce, ciphertext);
        return ENC_PREFIX + Base64.getEncoder().encodeToString(payload);
    }

    @Override
    @Transactional
    public String decrypt(String storedValue) {
        Objects.requireNonNull(storedValue, "storedValue must not be null");
        if (!storedValue.startsWith(ENC_PREFIX)) {
            return storedValue;
        }
        byte[] payload = Base64.getDecoder().decode(storedValue.substring(ENC_PREFIX.length()));
        byte[] subjectIdBytes = Arrays.copyOfRange(payload, 0, SUBJECT_ID_LENGTH_BYTES);
        byte[] nonce = Arrays.copyOfRange(
                payload, SUBJECT_ID_LENGTH_BYTES, SUBJECT_ID_LENGTH_BYTES + AesGcmCipher.NONCE_LENGTH_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(
                payload, SUBJECT_ID_LENGTH_BYTES + AesGcmCipher.NONCE_LENGTH_BYTES, payload.length);
        String subjectId = HexFormat.of().formatHex(subjectIdBytes);

        SubjectKeyRecord record = subjectKeyStore.findById(subjectId)
                .orElseThrow(() -> new IllegalStateException(
                        "missing subject key for encrypted field: " + subjectId));
        if (record.destroyed()) {
            return SHREDDED_MARKER;
        }
        byte[] dek = AesGcmCipher.decrypt(
                kekResolver.keyForVersion(record.kekVersion()), record.dekNonce(), record.wrappedDek());
        byte[] plaintext = AesGcmCipher.decrypt(new SecretKeySpec(dek, "AES"), nonce, ciphertext);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    @Override
    @Transactional
    public void shred(String plaintext) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        String subjectId = subjectIdDerivation.deriveHex(plaintext);
        subjectKeyStore.destroy(subjectId, clock.instant());
    }

    private byte[] resolveDekForEncryption(String subjectId) {
        Optional<SubjectKeyRecord> existing = subjectKeyStore.findById(subjectId);
        if (existing.isEmpty()) {
            byte[] dek = new byte[DEK_LENGTH_BYTES];
            secureRandom.nextBytes(dek);
            byte[] wrapNonce = AesGcmCipher.randomNonce(secureRandom);
            byte[] wrappedDek = AesGcmCipher.encrypt(
                    kekResolver.keyForVersion(kekResolver.activeVersion()), wrapNonce, dek);
            subjectKeyStore.insert(subjectId, wrappedDek, wrapNonce, kekResolver.activeVersion(), clock.instant());
            return dek;
        }
        SubjectKeyRecord record = existing.get();
        if (record.destroyed()) {
            throw new SubjectKeyDestroyedException(subjectId);
        }
        return AesGcmCipher.decrypt(
                kekResolver.keyForVersion(record.kekVersion()), record.dekNonce(), record.wrappedDek());
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] array : arrays) {
            total += array.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }
}
