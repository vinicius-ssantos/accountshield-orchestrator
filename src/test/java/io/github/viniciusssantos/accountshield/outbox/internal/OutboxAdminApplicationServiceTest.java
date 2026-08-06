package io.github.viniciusssantos.accountshield.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.outbox.OutboxEventNotDeadLetteredException;
import io.github.viniciusssantos.accountshield.outbox.OutboxEventNotFoundException;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventEntity;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

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

    @Test
    void listDelegatesBoundedFindAllToTheRepository() {
        Pageable pageable = PageRequest.of(0, 20);
        when(repository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(deadLetteredEvent()), pageable, 1));

        List<?> result = service.list(null, pageable);

        assertThat(result).hasSize(1);
        verify(repository).findAll(any(Pageable.class));
    }

    @Test
    void listAppliesTheStatusFilter() {
        Pageable pageable = PageRequest.of(0, 20);
        when(repository.findByStatus(eq("DEAD_LETTERED"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(deadLetteredEvent()), pageable, 1));

        List<?> result = service.list("DEAD_LETTERED", pageable);

        assertThat(result).hasSize(1);
        verify(repository).findByStatus(eq("DEAD_LETTERED"), any(Pageable.class));
    }

    @Test
    void listCapsThePageSizeAtTheMaximum() {
        when(repository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));

        service.list(null, PageRequest.of(0, 10_000));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(OutboxAdminApplicationService.MAX_PAGE_SIZE);
    }

    @Test
    void listSubstitutesTheDefaultPageSizeWhenNonPositiveIsRequested() {
        Pageable nonPositive = mock(Pageable.class);
        when(nonPositive.getPageSize()).thenReturn(0);
        when(nonPositive.getPageNumber()).thenReturn(0);
        when(repository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));

        service.list(null, nonPositive);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(OutboxAdminApplicationService.DEFAULT_PAGE_SIZE);
    }
}
