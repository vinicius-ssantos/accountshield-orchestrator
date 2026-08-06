package io.github.viniciusssantos.accountshield.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.audit.AuditChainBreak;
import io.github.viniciusssantos.accountshield.audit.AuditChainIntegrityFailed;
import io.github.viniciusssantos.accountshield.audit.AuditChainRootHash;
import io.github.viniciusssantos.accountshield.audit.AuditChainVerificationResult;
import io.github.viniciusssantos.accountshield.audit.AuditChainVerificationService;
import io.github.viniciusssantos.accountshield.audit.internal.persistence.AuditChainCheckpointStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class AuditChainIntegrityCheckJobTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final AuditChainVerificationService verificationService = mock(AuditChainVerificationService.class);
    private final AuditChainCheckpointStore checkpointStore = mock(AuditChainCheckpointStore.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void advancesCheckpointOnAValidBatch() {
        when(checkpointStore.lastVerifiedSequence()).thenReturn(10L);
        when(verificationService.currentRootHash())
                .thenReturn(Optional.of(new AuditChainRootHash(15, "hash", NOW)));
        when(verificationService.verifyRange(11, 15))
                .thenReturn(new AuditChainVerificationResult(5, true, List.of()));

        AuditChainIntegrityCheckJob job = newJob(500);
        job.verifyNextBatch();

        verify(checkpointStore).advanceTo(15, NOW);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void doesNotAdvancePastABreakAndPublishesAnEvent() {
        when(checkpointStore.lastVerifiedSequence()).thenReturn(10L);
        when(verificationService.currentRootHash())
                .thenReturn(Optional.of(new AuditChainRootHash(15, "hash", NOW)));
        AuditChainBreak brokenLink = new AuditChainBreak(12, "record_hash does not match recomputed content");
        when(verificationService.verifyRange(11, 15))
                .thenReturn(new AuditChainVerificationResult(5, false, List.of(brokenLink)));

        AuditChainIntegrityCheckJob job = newJob(500);
        job.verifyNextBatch();

        verify(checkpointStore, never()).advanceTo(anyLong(), any());
        org.mockito.ArgumentCaptor<AuditChainIntegrityFailed> captor =
                org.mockito.ArgumentCaptor.forClass(AuditChainIntegrityFailed.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().fromSequence()).isEqualTo(11);
        assertThat(captor.getValue().toSequence()).isEqualTo(15);
        assertThat(captor.getValue().breaks()).containsExactly(brokenLink);
    }

    @Test
    void doesNothingWhenNothingNewToVerify() {
        when(checkpointStore.lastVerifiedSequence()).thenReturn(15L);
        when(verificationService.currentRootHash())
                .thenReturn(Optional.of(new AuditChainRootHash(15, "hash", NOW)));

        AuditChainIntegrityCheckJob job = newJob(500);
        job.verifyNextBatch();

        verify(verificationService, never()).verifyRange(anyLong(), anyLong());
        verify(checkpointStore, never()).advanceTo(anyLong(), any());
    }

    @Test
    void batchSizeBoundsTheVerifiedRange() {
        when(checkpointStore.lastVerifiedSequence()).thenReturn(0L);
        when(verificationService.currentRootHash())
                .thenReturn(Optional.of(new AuditChainRootHash(1000, "hash", NOW)));
        when(verificationService.verifyRange(1, 100))
                .thenReturn(new AuditChainVerificationResult(100, true, List.of()));

        AuditChainIntegrityCheckJob job = newJob(100);
        job.verifyNextBatch();

        verify(checkpointStore).advanceTo(100, NOW);
    }

    private AuditChainIntegrityCheckJob newJob(int batchSize) {
        return new AuditChainIntegrityCheckJob(
                verificationService, checkpointStore, clock, batchSize, new SimpleMeterRegistry(), eventPublisher);
    }
}
