package io.github.viniciusssantos.accountshield.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.outbox.OutboxEventNotDeadLetteredException;
import io.github.viniciusssantos.accountshield.outbox.OutboxEventNotFoundException;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventEntity;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxAdminApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final OutboxAdminApplicationService service =
            new OutboxAdminApplicationService(repository, clock);

    @Test
    void requeueResetsADeadLetteredEventToPending() {
        OutboxEventEntity entity = deadLetteredEvent();
        when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

        service.requeue(entity.getId(), "operator-1");

        assertThat(entity.getStatus()).isEqualTo("PENDING");
        assertThat(entity.getAttemptCount()).isZero();
        assertThat(entity.getLastError()).isNull();
        assertThat(entity.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(entity.getDeadLetteredAt()).isNull();
    }

    @Test
    void requeueRejectsAnEventThatIsNotDeadLettered() {
        OutboxEventEntity entity = new OutboxEventEntity(
                UUID.randomUUID(), "ProtectionDecision", "dec-1", "PROTECTION_DECISION_MADE",
                "{}", NOW.minusSeconds(60));
        when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.requeue(entity.getId(), "operator-1"))
                .isInstanceOf(OutboxEventNotDeadLetteredException.class);
    }

    @Test
    void requeueThrowsWhenEventDoesNotExist() {
        UUID missingId = UUID.randomUUID();
        when(repository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requeue(missingId, "operator-1"))
                .isInstanceOf(OutboxEventNotFoundException.class);
    }

    private OutboxEventEntity deadLetteredEvent() {
        OutboxEventEntity entity = new OutboxEventEntity(
                UUID.randomUUID(), "ProtectionDecision", "dec-1", "PROTECTION_DECISION_MADE",
                "{}", NOW.minusSeconds(3600));
        for (int i = 0; i < 5; i++) {
            entity.recordFailure("failure " + i, NOW);
        }
        entity.markDeadLettered("final failure", NOW);
        return entity;
    }
}
