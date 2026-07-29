package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.crypto.internal.KeyEncryptionKeyResolver;
import io.github.viniciusssantos.accountshield.crypto.internal.SubjectKeyRewrapJob;
import io.github.viniciusssantos.accountshield.crypto.internal.persistence.SubjectKeyRecord;
import io.github.viniciusssantos.accountshield.crypto.internal.persistence.SubjectKeyStore;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class SubjectKeyRewrapJobTest {

    private static final int STALE_KEK_VERSION = 99;

    @Autowired
    private SubjectKeyRewrapJob rewrapJob;

    @Autowired
    private SubjectKeyStore subjectKeyStore;

    @Autowired
    private KeyEncryptionKeyResolver kekResolver;

    @Test
    void rewrapsOnlySubjectKeysOnANonActiveKekVersion() throws Exception {
        int activeVersion = kekResolver.activeVersion();
        SecretKeySpec activeKek = kekResolver.keyForVersion(activeVersion);
        SecretKeySpec staleKek = kekResolver.keyForVersion(STALE_KEK_VERSION);
        String upToDateSubjectId = insertSubjectKey(activeVersion, activeKek);
        String staleSubjectId = insertSubjectKey(STALE_KEK_VERSION, staleKek);

        rewrapJob.rewrapPendingSubjectKeys();

        SubjectKeyRecord upToDate = subjectKeyStore.findById(upToDateSubjectId).orElseThrow();
        assertThat(upToDate.rewrappedAt()).isNull();
        assertThat(upToDate.kekVersion()).isEqualTo(activeVersion);

        SubjectKeyRecord stale = subjectKeyStore.findById(staleSubjectId).orElseThrow();
        assertThat(stale.kekVersion()).isEqualTo(activeVersion);
        assertThat(stale.rewrappedAt()).isNotNull();

        byte[] dek = aesGcm(
                Cipher.DECRYPT_MODE, kekResolver.keyForVersion(activeVersion), stale.dekNonce(), stale.wrappedDek());
        assertThat(dek).hasSize(32);
    }

    /**
     * Issue #143 / F-19: a rewrap racing a crypto-shred must never resurrect the shredded key.
     * {@code destroy()} then {@code rewrap()} on the same subject simulates the shred landing
     * between the job's batch SELECT and its per-record UPDATE -- the rewrap must be a no-op.
     */
    @Test
    void rewrapDoesNotResurrectAnAlreadyDestroyedSubjectKey() throws Exception {
        SecretKeySpec staleKek = kekResolver.keyForVersion(STALE_KEK_VERSION);
        String subjectId = insertSubjectKey(STALE_KEK_VERSION, staleKek);
        Instant destroyedAt = Instant.now();

        subjectKeyStore.destroy(subjectId, destroyedAt);
        SubjectKeyRecord destroyed = subjectKeyStore.findById(subjectId).orElseThrow();
        assertThat(destroyed.destroyedAt()).isNotNull();
        assertThat(destroyed.wrappedDek()).isNull();

        byte[] fakeNewWrappedDek = new byte[48];
        new SecureRandom().nextBytes(fakeNewWrappedDek);
        byte[] fakeNewNonce = new byte[12];
        new SecureRandom().nextBytes(fakeNewNonce);
        subjectKeyStore.rewrap(subjectId, fakeNewWrappedDek, fakeNewNonce, kekResolver.activeVersion(), Instant.now());

        SubjectKeyRecord afterRewrap = subjectKeyStore.findById(subjectId).orElseThrow();
        assertThat(afterRewrap.destroyedAt()).isEqualTo(destroyed.destroyedAt());
        assertThat(afterRewrap.wrappedDek()).isNull();
        assertThat(afterRewrap.rewrappedAt()).isNull();
    }

    private String insertSubjectKey(int kekVersion, SecretKeySpec kek) throws Exception {
        String subjectId = UUID.randomUUID().toString().replace("-", "");
        byte[] dek = new byte[32];
        new SecureRandom().nextBytes(dek);
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        byte[] wrappedDek = aesGcm(Cipher.ENCRYPT_MODE, kek, nonce, dek);

        subjectKeyStore.insert(subjectId, wrappedDek, nonce, kekVersion, Instant.now());
        return subjectId;
    }

    private byte[] aesGcm(int mode, SecretKeySpec key, byte[] nonce, byte[] input) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        return cipher.doFinal(input);
    }
}
