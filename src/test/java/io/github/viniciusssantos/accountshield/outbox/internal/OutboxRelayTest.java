package io.github.viniciusssantos.accountshield.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.outbox.OutboxEventPublisher;
import io.github.viniciusssantos.accountshield.outbox.OutboxMessage;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventEntity;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;

class OutboxRelayTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-07-23T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final OutboxEventPublisher publisher = mock(OutboxEventPublisher.class);

    private OutboxRelay newRelay(int maxAttempts) {
        return new OutboxRelay(repository, publisher, FIXED_CLOCK, 50, maxAttempts);
    }

    @Test
    void marksEventAsPublishedWhenPublisherSucceeds() {
        OutboxEventEntity event = unpublishedEvent(0);
        when(repository.findUnpublished(any())).thenReturn(List.of(event));
        OutboxRelay relay = newRelay(5);

        relay.dispatchPending();

        ArgumentCaptor<OutboxMessage> messageCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(publisher).publish(messageCaptor.capture());
        assertThat(messageCaptor.getValue().eventType()).isEqualTo("PROTECTION_DECISION_MADE");
        assertThat(messageCaptor.getValue().aggregateId()).isEqualTo("dec-123");

        verify(repository).save(event);
        assertThat(event.getPublishedAt()).isEqualTo(FIXED_INSTANT);
    }

    @Test
    void incrementsAttemptCountAndRecordsErrorWhenPublisherFails() {
        OutboxEventEntity event = unpublishedEvent(0);
        when(repository.findUnpublished(any())).thenReturn(List.of(event));
        doThrow(new RuntimeException("connection refused")).when(publisher).publish(any());
        OutboxRelay relay = newRelay(5);

        relay.dispatchPending();

        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("connection refused");
        assertThat(event.getPublishedAt()).isNull();
        verify(repository).save(event);
    }

    @Test
    void skipsEventsThatExceedMaxAttempts() {
        OutboxEventEntity event = unpublishedEvent(5);
        when(repository.findUnpublished(any())).thenReturn(List.of(event));
        OutboxRelay relay = newRelay(5);

        relay.dispatchPending();

        verify(publisher, never()).publish(any());
        verify(repository, never()).save(event);
        assertThat(event.getAttemptCount()).isEqualTo(5);
    }

    @Test
    void handlesOptimisticLockingConflictGracefully() {
        OutboxEventEntity event = unpublishedEvent(0);
        when(repository.findUnpublished(any())).thenReturn(List.of(event));
        when(repository.save(event)).thenThrow(new OptimisticLockingFailureException("conflict"));
        OutboxRelay relay = newRelay(5);

        relay.dispatchPending();

        assertThat(event.getPublishedAt()).isEqualTo(FIXED_INSTANT);
    }

    @Test
    void boundsErrorMessageLength() {
        OutboxEventEntity event = unpublishedEvent(0);
        when(repository.findUnpublished(any())).thenReturn(List.of(event));
        String longError = "x".repeat(2000);
        doThrow(new RuntimeException(longError)).when(publisher).publish(any());
        OutboxRelay relay = newRelay(5);

        relay.dispatchPending();

        assertThat(event.getLastError()).hasSize(1000);
    }

    @Test
    void usesNullMessageWhenExceptionHasNoMessage() {
        OutboxEventEntity event = unpublishedEvent(0);
        when(repository.findUnpublished(any())).thenReturn(List.of(event));
        doThrow(new NullPointerException()).when(publisher).publish(any());
        OutboxRelay relay = newRelay(5);

        relay.dispatchPending();

        assertThat(event.getLastError()).isEqualTo("NullPointerException");
    }

    @Test
    void rejectsInvalidBatchSize() {
        assertThatThrownBy(() -> new OutboxRelay(repository, publisher, FIXED_CLOCK, 0, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
    }

    @Test
    void rejectsInvalidMaxAttempts() {
        assertThatThrownBy(() -> new OutboxRelay(repository, publisher, FIXED_CLOCK, 50, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
    }

    private OutboxEventEntity unpublishedEvent(int attemptCount) {
        OutboxEventEntity entity = new OutboxEventEntity(
                UUID.randomUUID(),
                "ProtectionDecision",
                "dec-123",
                "PROTECTION_DECISION_MADE",
                "{\"outcome\":\"ALLOW\"}",
                FIXED_INSTANT.minusSeconds(60));
        for (int i = 0; i < attemptCount; i++) {
            entity.recordFailure("previous failure", FIXED_INSTANT);
        }
        return entity;
    }
}
