package io.github.viniciusssantos.accountshield.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.outbox.OutboxEventPublisher;
import io.github.viniciusssantos.accountshield.outbox.OutboxMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OutboxRelayTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-07-26T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private final OutboxClaimStore claimStore = mock(OutboxClaimStore.class);
    private final OutboxEventPublisher publisher = mock(OutboxEventPublisher.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private OutboxRelay newRelay(int maxAttempts) {
        return new OutboxRelay(
                claimStore, publisher, FIXED_CLOCK, meterRegistry, 50, maxAttempts,
                Duration.ofMinutes(2), Duration.ofSeconds(1), Duration.ofMinutes(5));
    }

    @Test
    void marksEventAsPublishedWhenPublisherSucceeds() {
        ClaimedOutboxEvent event = claimedEvent(0);
        when(claimStore.claimBatch(any(), any(), anyString(), anyInt())).thenReturn(List.of(event));
        OutboxRelay relay = newRelay(5);

        relay.dispatchPending();

        ArgumentCaptor<OutboxMessage> messageCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(publisher).publish(messageCaptor.capture());
        assertThat(messageCaptor.getValue().eventType()).isEqualTo("PROTECTION_DECISION_MADE");
        assertThat(messageCaptor.getValue().aggregateId()).isEqualTo("dec-123");
        verify(claimStore).markPublished(event.id(), FIXED_INSTANT);
    }

    @Test
    void recordsBackoffAndIncrementsAttemptOnFailureBelowMaxAttempts() {
        ClaimedOutboxEvent event = claimedEvent(0);
        when(claimStore.claimBatch(any(), any(), anyString(), anyInt())).thenReturn(List.of(event));
        doThrow(new RuntimeException("connection refused")).when(publisher).publish(any());
        OutboxRelay relay = newRelay(5);

        relay.dispatchPending();

        ArgumentCaptor<Instant> nextAttemptCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(claimStore).markFailedWithBackoff(
                eq(event.id()), eq(1), eq("connection refused"), nextAttemptCaptor.capture());
        assertThat(nextAttemptCaptor.getValue()).isAfter(FIXED_INSTANT);
        verify(claimStore, never()).markDeadLettered(any(), anyInt(), any(), any());
    }

    @Test
    void deadLettersEventWhenAttemptCountReachesMaxAttempts() {
        ClaimedOutboxEvent event = claimedEvent(4);
        when(claimStore.claimBatch(any(), any(), anyString(), anyInt())).thenReturn(List.of(event));
        doThrow(new RuntimeException("still failing")).when(publisher).publish(any());
        OutboxRelay relay = newRelay(5);

        relay.dispatchPending();

        verify(claimStore).markDeadLettered(event.id(), 5, "still failing", FIXED_INSTANT);
        verify(claimStore, never()).markFailedWithBackoff(any(), anyInt(), any(), any());
    }

    @Test
    void boundsErrorMessageLength() {
        ClaimedOutboxEvent event = claimedEvent(0);
        when(claimStore.claimBatch(any(), any(), anyString(), anyInt())).thenReturn(List.of(event));
        String longError = "x".repeat(2000);
        doThrow(new RuntimeException(longError)).when(publisher).publish(any());
        OutboxRelay relay = newRelay(5);

        relay.dispatchPending();

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(claimStore).markFailedWithBackoff(any(), anyInt(), errorCaptor.capture(), any());
        assertThat(errorCaptor.getValue()).hasSize(1000);
    }

    @Test
    void usesExceptionClassNameWhenMessageIsNull() {
        ClaimedOutboxEvent event = claimedEvent(0);
        when(claimStore.claimBatch(any(), any(), anyString(), anyInt())).thenReturn(List.of(event));
        doThrow(new NullPointerException()).when(publisher).publish(any());
        OutboxRelay relay = newRelay(5);

        relay.dispatchPending();

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(claimStore).markFailedWithBackoff(any(), anyInt(), errorCaptor.capture(), any());
        assertThat(errorCaptor.getValue()).isEqualTo("NullPointerException");
    }

    @Test
    void rejectsInvalidBatchSize() {
        assertThatThrownBy(() -> new OutboxRelay(
                claimStore, publisher, FIXED_CLOCK, meterRegistry, 0, 5,
                Duration.ofMinutes(2), Duration.ofSeconds(1), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
    }

    @Test
    void rejectsInvalidMaxAttempts() {
        assertThatThrownBy(() -> new OutboxRelay(
                claimStore, publisher, FIXED_CLOCK, meterRegistry, 50, 0,
                Duration.ofMinutes(2), Duration.ofSeconds(1), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
    }

    private ClaimedOutboxEvent claimedEvent(int attemptCount) {
        return new ClaimedOutboxEvent(
                UUID.randomUUID(),
                "ProtectionDecision",
                "dec-123",
                "PROTECTION_DECISION_MADE",
                "{\"outcome\":\"ALLOW\"}",
                FIXED_INSTANT.minusSeconds(60),
                attemptCount);
    }
}
