package io.github.viniciusssantos.accountshield.outbox.internal;

import io.github.viniciusssantos.accountshield.outbox.OutboxAdminService;
import io.github.viniciusssantos.accountshield.outbox.OutboxEventNotFoundException;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventEntity;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OutboxAdminApplicationService implements OutboxAdminService {

    private static final Logger log = LoggerFactory.getLogger(OutboxAdminApplicationService.class);

    private final OutboxEventRepository repository;
    private final Clock clock;

    OutboxAdminApplicationService(OutboxEventRepository repository, @Qualifier("decisionClock") Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void requeue(UUID eventId, String actor) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");

        OutboxEventEntity entity = repository.findById(eventId)
                .orElseThrow(() -> new OutboxEventNotFoundException(eventId));
        entity.requeue(clock.instant());
        log.info("outbox_event_requeued event_id={} actor={}", eventId, actor);
    }
}
