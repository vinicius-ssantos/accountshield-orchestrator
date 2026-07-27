package io.github.viniciusssantos.accountshield.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.PostgreSqlTestConfiguration;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventEntity;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Issue #39: "process failure after publish and before outbox acknowledgement." A relay instance
 * that claims an event (status IN_PROGRESS) and then crashes before ever calling
 * markPublished/markFailedWithBackoff/markDeadLettered leaves an abandoned claim behind. This
 * proves such a claim becomes reclaimable once its claimed_at is older than a caller-supplied
 * stale-claim cutoff -- the at-least-once guarantee {@code OutboxRelay} relies on in production
 * (ADR 0023), exercised here directly against {@code OutboxClaimStore} without needing to actually
 * crash a process.
 */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class OutboxReclaimAfterProcessFailureTest {

    @Autowired
    private OutboxClaimStore claimStore;

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aClaimAbandonedByACrashedProcessBecomesReclaimable() {
        UUID id = UUID.randomUUID();
        // occurred_at far in the past so this row always sorts first in the claim query,
        // ahead of any PENDING/IN_PROGRESS rows other tests may have left behind in this shared
        // Postgres instance (matches OutboxClaimStoreConcurrencyTest's own technique).
        repository.save(new OutboxEventEntity(
                id, "Test", "agg-" + id, "TEST_EVENT", "{}", Instant.now().minusSeconds(999_999)));

        Instant abandonedClaimedAt = Instant.now().minus(Duration.ofMinutes(10));
        jdbcTemplate.update(
                "UPDATE outbox.outbox_event SET status = 'IN_PROGRESS', claimed_at = ?, claimed_by = ? WHERE id = ?",
                Timestamp.from(abandonedClaimedAt), "crashed-instance", id);

        Instant now = Instant.now();
        Instant staleClaimCutoff = now.minus(Duration.ofMinutes(2));
        List<ClaimedOutboxEvent> reclaimed =
                claimStore.claimBatch(now, staleClaimCutoff, "recovering-instance", 50);

        assertThat(reclaimed).extracting(ClaimedOutboxEvent::id).contains(id);
    }

    @Test
    void aRecentlyClaimedEventIsNotYetConsideredAbandoned() {
        UUID id = UUID.randomUUID();
        repository.save(new OutboxEventEntity(
                id, "Test", "agg-" + id, "TEST_EVENT", "{}", Instant.now().minusSeconds(999_998)));

        Instant recentlyClaimedAt = Instant.now();
        jdbcTemplate.update(
                "UPDATE outbox.outbox_event SET status = 'IN_PROGRESS', claimed_at = ?, claimed_by = ? WHERE id = ?",
                Timestamp.from(recentlyClaimedAt), "still-working-instance", id);

        Instant now = Instant.now();
        Instant staleClaimCutoff = now.minus(Duration.ofMinutes(2));
        List<ClaimedOutboxEvent> reclaimed =
                claimStore.claimBatch(now, staleClaimCutoff, "recovering-instance", 50);

        assertThat(reclaimed).extracting(ClaimedOutboxEvent::id).doesNotContain(id);
    }
}
