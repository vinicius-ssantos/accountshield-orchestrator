package io.github.viniciusssantos.accountshield.crypto.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.crypto.FieldEncryptionService;
import io.github.viniciusssantos.accountshield.crypto.SubjectKeyDestroyedException;
import io.github.viniciusssantos.accountshield.crypto.internal.persistence.SubjectKeyRecord;
import io.github.viniciusssantos.accountshield.crypto.internal.persistence.SubjectKeyStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AesGcmFieldEncryptionServiceTest {

    private final Map<String, SubjectKeyRecord> store = new HashMap<>();
    private FakeSubjectKeyStore subjectKeyStore;
    private AesGcmFieldEncryptionService service;

    @BeforeEach
    void setUp() {
        store.clear();
        subjectKeyStore = new FakeSubjectKeyStore();
        KeyEncryptionKeyResolver kekResolver = new KeyEncryptionKeyResolver(1, "D/d4CZZMPi+4f/3+7JoUrg0QEuVrXVNgQ8YTNigBcPk=", 0, "");
        SubjectIdDerivation subjectIdDerivation = new SubjectIdDerivation("test-subject-id-secret");
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        service = new AesGcmFieldEncryptionService(subjectKeyStore, subjectIdDerivation, kekResolver, clock);
    }

    @Test
    void encryptsAndDecryptsRoundTrip() {
        String stored = service.encrypt("acct-42");

        assertThat(stored).startsWith("ENC:").isNotEqualTo("acct-42");
        assertThat(service.decrypt(stored)).isEqualTo("acct-42");
    }

    @Test
    void decryptPassesThroughLegacyPlaintext() {
        assertThat(service.decrypt("legacy-plaintext-value")).isEqualTo("legacy-plaintext-value");
    }

    @Test
    void repeatedEncryptionsOfTheSameSubjectReuseOneSubjectKey() {
        String first = service.encrypt("acct-42");
        String second = service.encrypt("acct-42");

        assertThat(first).isNotEqualTo(second);
        assertThat(store).hasSize(1);
        assertThat(service.decrypt(first)).isEqualTo("acct-42");
        assertThat(service.decrypt(second)).isEqualTo("acct-42");
    }

    @Test
    void shredMakesTheValueIrrecoverable() {
        String stored = service.encrypt("acct-42");

        service.shred("acct-42");

        assertThat(service.decrypt(stored)).isEqualTo(FieldEncryptionService.SHREDDED_MARKER);
    }

    @Test
    void shredIsIdempotent() {
        service.encrypt("acct-42");

        service.shred("acct-42");
        service.shred("acct-42");

        assertThat(store.values()).singleElement()
                .satisfies(record -> assertThat(record.destroyed()).isTrue());
    }

    @Test
    void shreddingASubjectThatWasNeverEncryptedIsANoOp() {
        service.shred("never-seen-acct");

        assertThat(store).isEmpty();
    }

    @Test
    void encryptingAfterShredThrows() {
        service.encrypt("acct-42");
        service.shred("acct-42");

        assertThatThrownBy(() -> service.encrypt("acct-42"))
                .isInstanceOf(SubjectKeyDestroyedException.class);
    }

    @Test
    void decryptingAnEncryptedValueWhoseSubjectKeyRowIsMissingFailsLoudly() {
        String stored = service.encrypt("acct-42");
        store.clear();

        assertThatThrownBy(() -> service.decrypt(stored))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing subject key");
    }

    /** In-memory fake standing in for the real JdbcTemplate-backed store. */
    private final class FakeSubjectKeyStore extends SubjectKeyStore {

        FakeSubjectKeyStore() {
            super(null);
        }

        @Override
        public Optional<SubjectKeyRecord> findById(String subjectId) {
            return Optional.ofNullable(store.get(subjectId));
        }

        @Override
        public void insert(String subjectId, byte[] wrappedDek, byte[] dekNonce, int kekVersion, Instant createdAt) {
            store.put(subjectId, new SubjectKeyRecord(subjectId, wrappedDek, dekNonce, kekVersion, createdAt, null, null));
        }

        @Override
        public void rewrap(String subjectId, byte[] newWrappedDek, byte[] newNonce, int newKekVersion, Instant now) {
            SubjectKeyRecord existing = store.get(subjectId);
            store.put(subjectId, new SubjectKeyRecord(
                    subjectId, newWrappedDek, newNonce, newKekVersion, existing.createdAt(), now, null));
        }

        @Override
        public void destroy(String subjectId, Instant now) {
            SubjectKeyRecord existing = store.get(subjectId);
            if (existing == null || existing.destroyed()) {
                return;
            }
            store.put(subjectId, new SubjectKeyRecord(
                    subjectId, null, null, null, existing.createdAt(), existing.rewrappedAt(), now));
        }
    }
}
