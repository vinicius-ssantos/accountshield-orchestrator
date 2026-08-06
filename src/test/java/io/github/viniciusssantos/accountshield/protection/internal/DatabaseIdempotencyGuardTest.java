package io.github.viniciusssantos.accountshield.protection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.protection.ConflictingIdempotencyRequestException;
import io.github.viniciusssantos.accountshield.protection.IdempotencyResult;
import io.github.viniciusssantos.accountshield.protection.internal.persistence.IdempotencyRecordEntity;
import io.github.viniciusssantos.accountshield.protection.internal.persistence.IdempotencyRecordRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DatabaseIdempotencyGuardTest {

    private static final Instant NOW = Instant.parse("2026-07-20T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String CLIENT_A = "client-a";

    private final IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final DatabaseIdempotencyGuard guard =
            new DatabaseIdempotencyGuard(repository, CLOCK, Duration.ofHours(24), meterRegistry);

    @Test
    void claimWinsWhenNoRowExists() {
        when(repository.insertIfAbsent(any(), eq(CLIENT_A), eq("key-1"), eq("fp-1"), anyString(), any(), any(), any()))
                .thenReturn(1);

        IdempotencyResult result = guard.claim(CLIENT_A, "key-1", "fp-1", UUID.randomUUID(), NOW);

        assertThat(result.duplicate()).isFalse();
        assertThat(counter("MISS")).isEqualTo(1.0);
    }

    @Test
    void claimReturnsDuplicateWhenExistingFingerprintMatches() {
        UUID resourceId = UUID.randomUUID();
        when(repository.insertIfAbsent(any(), eq(CLIENT_A), eq("key-1"), eq("fp-1"), anyString(), any(), any(), any()))
                .thenReturn(0);
        when(repository.findByClientIdAndIdempotencyKey(CLIENT_A, "key-1")).thenReturn(Optional.of(
                activeRecord(CLIENT_A, "key-1", "fp-1", resourceId, "{}")));

        IdempotencyResult result = guard.claim(CLIENT_A, "key-1", "fp-1", UUID.randomUUID(), NOW);

        assertThat(result.duplicate()).isTrue();
        assertThat(result.fingerprint()).isEqualTo("fp-1");
        assertThat(result.protectionRequestId()).isEqualTo(resourceId);
        assertThat(counter("HIT") + counter("RACE")).isEqualTo(1.0);
    }

    @Test
    void claimThrowsConflictWhenFingerprintsDiffer() {
        when(repository.insertIfAbsent(
                        any(), eq(CLIENT_A), eq("key-1"), eq("fp-different"), anyString(), any(), any(), any()))
                .thenReturn(0);
        when(repository.findByClientIdAndIdempotencyKey(CLIENT_A, "key-1")).thenReturn(Optional.of(
                activeRecord(CLIENT_A, "key-1", "fp-original", UUID.randomUUID(), "{}")));

        assertThatThrownBy(() -> guard.claim(CLIENT_A, "key-1", "fp-different", UUID.randomUUID(), NOW))
                .isInstanceOf(ConflictingIdempotencyRequestException.class);
        assertThat(counter("CONFLICT")).isEqualTo(1.0);
    }

    @Test
    void claimThrowsConflictWhenExistingRowHasNoPayloadYet() {
        when(repository.insertIfAbsent(any(), eq(CLIENT_A), eq("key-1"), eq("fp-1"), anyString(), any(), any(), any()))
                .thenReturn(0);
        when(repository.findByClientIdAndIdempotencyKey(CLIENT_A, "key-1")).thenReturn(Optional.of(
                activeRecord(CLIENT_A, "key-1", "fp-1", UUID.randomUUID(), null)));

        assertThatThrownBy(() -> guard.claim(CLIENT_A, "key-1", "fp-1", UUID.randomUUID(), NOW))
                .isInstanceOf(ConflictingIdempotencyRequestException.class);
    }

    @Test
    void claimReplacesExpiredRowAndWins() {
        when(repository.insertIfAbsent(any(), eq(CLIENT_A), eq("key-1"), eq("fp-1"), anyString(), any(), any(), any()))
                .thenReturn(0)
                .thenReturn(1);
        when(repository.findByClientIdAndIdempotencyKey(CLIENT_A, "key-1")).thenReturn(Optional.of(
                expiredRecord(CLIENT_A, "key-1", "fp-old")));

        IdempotencyResult result = guard.claim(CLIENT_A, "key-1", "fp-1", UUID.randomUUID(), NOW);

        assertThat(result.duplicate()).isFalse();
        verify(repository).deleteByClientIdAndIdempotencyKey(CLIENT_A, "key-1");
        assertThat(counter("EXPIRED")).isEqualTo(1.0);
        assertThat(counter("MISS")).isEqualTo(1.0);
    }

    @Test
    void claimAfterExpiryStillConflictsWhenAnotherRacerWonFirst() {
        when(repository.insertIfAbsent(any(), eq(CLIENT_A), eq("key-1"), eq("fp-1"), anyString(), any(), any(), any()))
                .thenReturn(0)
                .thenReturn(0);
        when(repository.findByClientIdAndIdempotencyKey(CLIENT_A, "key-1"))
                .thenReturn(Optional.of(expiredRecord(CLIENT_A, "key-1", "fp-old")))
                .thenReturn(Optional.of(activeRecord(CLIENT_A, "key-1", "fp-1", UUID.randomUUID(), "{}")));

        IdempotencyResult result = guard.claim(CLIENT_A, "key-1", "fp-1", UUID.randomUUID(), NOW);

        assertThat(result.duplicate()).isTrue();
        verify(repository).deleteByClientIdAndIdempotencyKey(CLIENT_A, "key-1");
        verify(repository, times(2)).insertIfAbsent(any(), eq(CLIENT_A), eq("key-1"), eq("fp-1"),
                anyString(), any(), any(), any());
    }

    @Test
    void finalizeResultUpdatesResponsePayload() {
        guard.finalizeResult(CLIENT_A, "key-1", "{\"outcome\":\"ALLOW\"}");

        verify(repository).updateResponsePayload(CLIENT_A, "key-1", "{\"outcome\":\"ALLOW\"}");
    }

    @Test
    void rejectsNullInputsOnClaim() {
        assertThatThrownBy(() -> guard.claim(null, "key", "fp", UUID.randomUUID(), NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> guard.claim(CLIENT_A, null, "fp", UUID.randomUUID(), NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> guard.claim(CLIENT_A, "key", null, UUID.randomUUID(), NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> guard.claim(CLIENT_A, "key", "fp", null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> guard.claim(CLIENT_A, "key", "fp", UUID.randomUUID(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullInputsOnFinalizeResult() {
        assertThatThrownBy(() -> guard.finalizeResult(null, "key", "{}"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> guard.finalizeResult(CLIENT_A, null, "{}"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> guard.finalizeResult(CLIENT_A, "key", null))
                .isInstanceOf(NullPointerException.class);
    }

    private double counter(String outcome) {
        var counter = meterRegistry.find("accountshield.protection.idempotency").tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private IdempotencyRecordEntity activeRecord(
            String clientId, String key, String fp, UUID resourceId, String payload) {
        return new IdempotencyRecordEntity(
                UUID.randomUUID(), clientId, key, fp, "protection_decision", resourceId,
                payload, NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(23)));
    }

    private IdempotencyRecordEntity expiredRecord(String clientId, String key, String fp) {
        return new IdempotencyRecordEntity(
                UUID.randomUUID(), clientId, key, fp, "protection_decision", UUID.randomUUID(),
                "{}", NOW.minus(Duration.ofHours(25)), NOW.minus(Duration.ofHours(1)));
    }
}
