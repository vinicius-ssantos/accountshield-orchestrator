package io.github.viniciusssantos.accountshield.protection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.dao.DataIntegrityViolationException;

class DatabaseIdempotencyGuardTest {

    private static final Instant NOW = Instant.parse("2026-07-20T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String CLIENT_A = "client-a";
    private static final String CLIENT_B = "client-b";

    private final IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
    private final DatabaseIdempotencyGuard guard = new DatabaseIdempotencyGuard(repository, CLOCK);

    @Test
    void returnsAbsentWhenNoRecordExists() {
        when(repository.findByClientIdAndIdempotencyKey(CLIENT_A, "key-1")).thenReturn(Optional.empty());

        IdempotencyResult result = guard.resolve(CLIENT_A, "key-1", "fp-1", NOW);

        assertThat(result.duplicate()).isFalse();
    }

    @Test
    void returnsAbsentWhenExistingRecordHasExpired() {
        when(repository.findByClientIdAndIdempotencyKey(CLIENT_A, "key-1"))
                .thenReturn(Optional.of(expiredRecord(CLIENT_A, "key-1", "fp-1")));

        IdempotencyResult result = guard.resolve(CLIENT_A, "key-1", "fp-1", NOW);

        assertThat(result.duplicate()).isFalse();
    }

    @Test
    void returnsDuplicateWhenFingerprintsMatch() {
        UUID resourceId = UUID.randomUUID();
        when(repository.findByClientIdAndIdempotencyKey(CLIENT_A, "key-1")).thenReturn(Optional.of(
                activeRecord(CLIENT_A, "key-1", "fp-1", resourceId, "{}")));

        IdempotencyResult result = guard.resolve(CLIENT_A, "key-1", "fp-1", NOW);

        assertThat(result.duplicate()).isTrue();
        assertThat(result.fingerprint()).isEqualTo("fp-1");
        assertThat(result.protectionRequestId()).isEqualTo(resourceId);
    }

    @Test
    void throwsConflictWhenFingerprintsDiffer() {
        when(repository.findByClientIdAndIdempotencyKey(CLIENT_A, "key-1")).thenReturn(Optional.of(
                activeRecord(CLIENT_A, "key-1", "fp-original", UUID.randomUUID(), "{}")));

        assertThatThrownBy(() -> guard.resolve(CLIENT_A, "key-1", "fp-different", NOW))
                .isInstanceOf(ConflictingIdempotencyRequestException.class)
                .hasMessageContaining("idempotency key");
    }

    @Test
    void twoDifferentClientsReusingTheSameIdempotencyKeyDoNotCollide() {
        when(repository.findByClientIdAndIdempotencyKey(CLIENT_A, "shared-key"))
                .thenReturn(Optional.of(activeRecord(CLIENT_A, "shared-key", "fp-a", UUID.randomUUID(), "{}")));
        when(repository.findByClientIdAndIdempotencyKey(CLIENT_B, "shared-key"))
                .thenReturn(Optional.empty());

        IdempotencyResult resultForClientA = guard.resolve(CLIENT_A, "shared-key", "fp-a", NOW);
        IdempotencyResult resultForClientB = guard.resolve(CLIENT_B, "shared-key", "fp-b", NOW);

        assertThat(resultForClientA.duplicate()).isTrue();
        assertThat(resultForClientB.duplicate()).isFalse();
    }

    @Test
    void rejectsNullInputs() {
        assertThatThrownBy(() -> guard.resolve(null, "key", "fp", NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> guard.resolve(CLIENT_A, null, "fp", NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> guard.resolve(CLIENT_A, "key", null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> guard.resolve(CLIENT_A, "key", "fp", null))
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

    @Test
    void recordTranslatesConcurrentConstraintViolationIntoConflict() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> guard.record(
                CLIENT_A, "key-1", "fp-1", "protection_decision", UUID.randomUUID(), "{}", NOW, NOW.plusSeconds(300)))
                .isInstanceOf(ConflictingIdempotencyRequestException.class);
    }

    private IdempotencyRecordEntity activeRecord(String clientId, String key, String fp, UUID resourceId,
            String payload) {
        return new IdempotencyRecordEntity(
                UUID.randomUUID(), clientId, key, fp, "protection_decision", resourceId,
                payload, NOW.minus(java.time.Duration.ofHours(1)), NOW.plus(java.time.Duration.ofHours(23)));
    }

    private IdempotencyRecordEntity expiredRecord(String clientId, String key, String fp) {
        return new IdempotencyRecordEntity(
                UUID.randomUUID(), clientId, key, fp, "protection_decision", UUID.randomUUID(),
                "{}", NOW.minus(java.time.Duration.ofHours(25)), NOW.minus(java.time.Duration.ofHours(1)));
    }
}
