package io.github.viniciusssantos.accountshield.protection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.protection.ConflictingIdempotencyRequestException;
import io.github.viniciusssantos.accountshield.protection.IdempotencyResult;
import io.github.viniciusssantos.accountshield.protection.internal.persistence.IdempotencyRecordEntity;
import io.github.viniciusssantos.accountshield.protection.internal.persistence.IdempotencyRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DatabaseIdempotencyGuardTest {

    private static final Instant NOW = Instant.parse("2026-07-20T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
    private final DatabaseIdempotencyGuard guard = new DatabaseIdempotencyGuard(repository, CLOCK);

    @Test
    void returnsAbsentWhenNoRecordExists() {
        when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());

        IdempotencyResult result = guard.resolve("key-1", "fp-1", NOW);

        assertThat(result.duplicate()).isFalse();
    }

    @Test
    void returnsAbsentWhenExistingRecordHasExpired() {
        when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(expiredRecord("key-1", "fp-1")));

        IdempotencyResult result = guard.resolve("key-1", "fp-1", NOW);

        assertThat(result.duplicate()).isFalse();
    }

    @Test
    void returnsDuplicateWhenFingerprintsMatch() {
        UUID resourceId = UUID.randomUUID();
        when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(
                activeRecord("key-1", "fp-1", resourceId, "{}")));

        IdempotencyResult result = guard.resolve("key-1", "fp-1", NOW);

        assertThat(result.duplicate()).isTrue();
        assertThat(result.fingerprint()).isEqualTo("fp-1");
        assertThat(result.protectionRequestId()).isEqualTo(resourceId);
    }

    @Test
    void throwsConflictWhenFingerprintsDiffer() {
        when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(
                activeRecord("key-1", "fp-original", UUID.randomUUID(), "{}")));

        assertThatThrownBy(() -> guard.resolve("key-1", "fp-different", NOW))
                .isInstanceOf(ConflictingIdempotencyRequestException.class)
                .hasMessageContaining("idempotency key");
    }

    @Test
    void rejectsNullInputs() {
        assertThatThrownBy(() -> guard.resolve(null, "fp", NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> guard.resolve("key", null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> guard.resolve("key", "fp", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void computesDefaultExpiry24HoursAfterCreation() {
        Instant createdAt = Instant.parse("2026-07-20T03:00:00Z");
        assertThat(guard.defaultExpiry(createdAt))
                .isEqualTo(createdAt.plus(java.time.Duration.ofHours(24)));
    }

    @Test
    void resourceTypeIsProtectionDecision() {
        assertThat(guard.resourceType()).isEqualTo("protection_decision");
    }

    private IdempotencyRecordEntity activeRecord(String key, String fp, UUID resourceId, String payload) {
        return new IdempotencyRecordEntity(
                UUID.randomUUID(), key, fp, "protection_decision", resourceId,
                payload, NOW.minusHours(1), NOW.plusHours(23));
    }

    private IdempotencyRecordEntity expiredRecord(String key, String fp) {
        return new IdempotencyRecordEntity(
                UUID.randomUUID(), key, fp, "protection_decision", UUID.randomUUID(),
                "{}", NOW.minusHours(25), NOW.minusHours(1));
    }
}
