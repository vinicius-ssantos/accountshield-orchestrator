package io.github.viniciusssantos.accountshield.outbox.internal.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    @Query("select e from OutboxEventEntity e where e.publishedAt is null order by e.occurredAt asc")
    List<OutboxEventEntity> findUnpublished(Pageable pageable);
}
