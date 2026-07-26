package io.github.viniciusssantos.accountshield.outbox.internal.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    long countByStatus(String status);

    List<OutboxEventEntity> findByStatus(String status);

    @Query("select min(e.occurredAt) from OutboxEventEntity e where e.status = 'PENDING'")
    Optional<Instant> findOldestPendingOccurredAt();

    @Modifying
    @Query(value = """
            DELETE FROM outbox.outbox_event
            WHERE id IN (
                SELECT id FROM outbox.outbox_event
                WHERE status = 'PUBLISHED' AND published_at < :cutoff
                ORDER BY published_at
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deletePublishedBatch(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    @Modifying
    @Query(value = """
            DELETE FROM outbox.outbox_event
            WHERE id IN (
                SELECT id FROM outbox.outbox_event
                WHERE status = 'DEAD_LETTERED' AND dead_lettered_at < :cutoff
                ORDER BY dead_lettered_at
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteDeadLetteredBatch(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
