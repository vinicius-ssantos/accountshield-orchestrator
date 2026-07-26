package io.github.viniciusssantos.accountshield.crypto.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.crypto.FieldEncryptionService;
import io.github.viniciusssantos.accountshield.crypto.SubjectKeyDestroyedException;
import io.github.viniciusssantos.accountshield.crypto.internal.persistence.SubjectKeyEntity;
import io.github.viniciusssantos.accountshield.crypto.internal.persistence.SubjectKeyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AesGcmFieldEncryptionServiceTest {

    private final Map<String, SubjectKeyEntity> store = new HashMap<>();
    private AesGcmFieldEncryptionService service;

    @BeforeEach
    void setUp() {
        store.clear();
        SubjectKeyRepository repository = mock(SubjectKeyRepository.class);
        when(repository.findById(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(store.get(invocation.getArgument(0))));
        when(repository.save(any())).thenAnswer(invocation -> {
            SubjectKeyEntity entity = invocation.getArgument(0);
            store.put(entity.subjectId(), entity);
            return entity;
        });
        KeyEncryptionKeyResolver kekResolver = new KeyEncryptionKeyResolver(1, "test-active-kek", 0, "");
        SubjectIdDerivation subjectIdDerivation = new SubjectIdDerivation("test-subject-id-secret");
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        service = new AesGcmFieldEncryptionService(repository, subjectIdDerivation, kekResolver, clock);
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
                .satisfies(entity -> assertThat(entity.destroyedAt()).isNotNull());
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
}
