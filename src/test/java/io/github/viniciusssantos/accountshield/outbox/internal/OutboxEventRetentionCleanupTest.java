package io.github.viniciusssantos.accountshield.outbox.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class OutboxEventRetentionCleanupTest {

    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private OutboxEventRetentionCleanup newCleanup(int batchSize) {
        return new OutboxEventRetentionCleanup(
                repository, clock, Duration.ofDays(7), Duration.ofDays(30), batchSize);
    }

    @Test
    void purgesPublishedAndDeadLetteredBatchesUntilFewerThanBatchSizeRemain() {
        when(repository.deletePublishedBatch(any(), anyInt())).thenReturn(500, 500, 20);
        when(repository.deleteDeadLetteredBatch(any(), anyInt())).thenReturn(3);
        OutboxEventRetentionCleanup cleanup = newCleanup(500);

        cleanup.purgeExpiredRecords();

        verify(repository, times(3)).deletePublishedBatch(any(), anyInt());
        verify(repository, times(1)).deleteDeadLetteredBatch(any(), anyInt());
    }

    @Test
    void usesConfiguredTtlsAsCutoffs() {
        when(repository.deletePublishedBatch(any(), anyInt())).thenReturn(0);
        when(repository.deleteDeadLetteredBatch(any(), anyInt())).thenReturn(0);
        OutboxEventRetentionCleanup cleanup = newCleanup(500);

        cleanup.purgeExpiredRecords();

        verify(repository).deletePublishedBatch(NOW.minus(Duration.ofDays(7)), 500);
        verify(repository).deleteDeadLetteredBatch(NOW.minus(Duration.ofDays(30)), 500);
    }

    @Test
    void doesNothingWhenNothingIsExpired() {
        when(repository.deletePublishedBatch(any(), anyInt())).thenReturn(0);
        when(repository.deleteDeadLetteredBatch(any(), anyInt())).thenReturn(0);
        OutboxEventRetentionCleanup cleanup = newCleanup(500);

        cleanup.purgeExpiredRecords();

        verify(repository, times(1)).deletePublishedBatch(any(), anyInt());
        verify(repository, times(1)).deleteDeadLetteredBatch(any(), anyInt());
    }

    @Test
    void rejectsInvalidBatchSize() {
        assertThatThrownBy(() -> newCleanup(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
    }
}
