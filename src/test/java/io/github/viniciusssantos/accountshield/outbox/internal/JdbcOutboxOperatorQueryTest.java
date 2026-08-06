package io.github.viniciusssantos.accountshield.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.PostgreSqlTestConfiguration;
import io.github.viniciusssantos.accountshield.outbox.OutboxOperatorQuery;
import io.github.viniciusssantos.accountshield.outbox.OutboxOperatorQuery.OutboxHealthSummary;
import io.github.viniciusssantos.accountshield.outbox.OutboxOperatorQuery.OutboxOperatorEventRecord;
import io.github.viniciusssantos.accountshield.outbox.OutboxOperatorQuery.OutboxOperatorSearchCriteria;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventEntity;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * This Postgres instance is shared with other test classes (issue #164): health counts are
 * global aggregates with no per-test scope, so assertions on them use before/after deltas rather
 * than absolute values. The event list/pagination assertions instead filter by a unique {@code
 * eventType} seeded only by this test, which fully isolates them from any other test's rows.
 */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class JdbcOutboxOperatorQueryTest {

    @Autowired
    private OutboxOperatorQuery query;

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void searchFiltersByEventTypeAndPaginatesNewestFirst() {
        String eventType = uniqueEventType();
        Instant base = Instant.parse("2026-07-01T00:00:00Z");
        UUID oldest = seedPending(eventType, base, 0);
        UUID middle = seedPending(eventType, base.plusSeconds(1), 0);
        UUID newest = seedPending(eventType, base.plusSeconds(2), 0);

        OutboxOperatorSearchCriteria firstPage = criteria(eventType, null, 2);
        var firstResult = query.search(firstPage).events();
        assertThat(firstResult.hasMore()).isTrue();
        assertThat(firstResult.nextCursor()).isNotBlank();
        assertThat(firstResult.records()).extracting(OutboxOperatorEventRecord::eventId)
                .containsExactly(newest, middle);

        OutboxOperatorSearchCriteria secondPage = criteria(eventType, firstResult.nextCursor(), 2);
        var secondResult = query.search(secondPage).events();
        assertThat(secondResult.hasMore()).isFalse();
        assertThat(secondResult.nextCursor()).isNull();
        assertThat(secondResult.records()).extracting(OutboxOperatorEventRecord::eventId)
                .containsExactly(oldest);
    }

    @Test
    void searchFiltersByAttemptCountRange() {
        String eventType = uniqueEventType();
        Instant now = Instant.now();
        seedWithAttempts(eventType, now, 0);
        UUID inRange = seedWithAttempts(eventType, now.plusSeconds(1), 1);
        seedWithAttempts(eventType, now.plusSeconds(2), 3);

        OutboxOperatorSearchCriteria bounded = new OutboxOperatorSearchCriteria(
                List.of(), eventType, null, null, 1, 2, null, OutboxOperatorQuery.DEFAULT_PAGE_SIZE);

        var result = query.search(bounded).events();
        assertThat(result.records()).extracting(OutboxOperatorEventRecord::eventId)
                .containsExactly(inRange);
    }

    @Test
    void deadLetteredRecordExposesSafeFailureCategoryAndMaskedCorrelationButNeverRawMessage() {
        String eventType = uniqueEventType();
        UUID id = UUID.randomUUID();
        String aggregateId = "recovery-authorization-abcd1234";
        repository.save(new OutboxEventEntity(
                id, "Recovery", aggregateId, eventType,
                "{\"schemaVersion\":\"integration-event-1.0\"}", Instant.now()));
        jdbcTemplate.update("""
                UPDATE outbox.outbox_event
                   SET status = 'DEAD_LETTERED', attempt_count = 5, dead_lettered_at = ?,
                       last_error = 'Connection refused to internal-secret-host:5432',
                       last_error_category = 'ConnectException'
                 WHERE id = ?
                """, Timestamp.from(Instant.now()), id);

        var result = query.search(criteria(eventType, null, OutboxOperatorQuery.DEFAULT_PAGE_SIZE)).events();
        assertThat(result.records()).hasSize(1);
        OutboxOperatorEventRecord record = result.records().get(0);

        assertThat(record.status()).isEqualTo("DEAD_LETTERED");
        assertThat(record.deadLetterReasonAvailable()).isTrue();
        assertThat(record.deadLetterFailureCategory()).isEqualTo("ConnectException");
        assertThat(record.nextAttemptAt()).isNull();
        assertThat(record.schemaVersion()).isEqualTo("integration-event-1.0");
        assertThat(record.maskedCorrelationReference()).isEqualTo("••••1234");
        assertThat(record.toString()).doesNotContain("internal-secret-host");
    }

    @Test
    void claimedRowsReportClaimStateAndTiming() {
        String eventType = uniqueEventType();
        UUID id = UUID.randomUUID();
        repository.save(new OutboxEventEntity(id, "Test", "agg-1", eventType, "{}", Instant.now()));
        Instant claimedAt = Instant.now();
        jdbcTemplate.update(
                "UPDATE outbox.outbox_event SET status = 'IN_PROGRESS', claimed_at = ?, claimed_by = ? WHERE id = ?",
                Timestamp.from(claimedAt), "instance-test", id);

        var result = query.search(criteria(eventType, null, OutboxOperatorQuery.DEFAULT_PAGE_SIZE)).events();
        OutboxOperatorEventRecord record = result.records().get(0);

        assertThat(record.claimed()).isTrue();
        assertThat(record.claimedAt()).isNotNull();
        assertThat(record.claimedAt()).isBetween(claimedAt.minusSeconds(1), claimedAt.plusSeconds(1));
    }

    @Test
    void healthSummaryDeltasReflectSeededBuckets() {
        OutboxHealthSummary before = query.search(emptyCriteria()).health();

        String eventType = uniqueEventType();
        Instant now = Instant.now();
        seedWithAttempts(eventType, now, 0);
        seedWithAttempts(eventType, now.plusSeconds(1), 0);
        seedWithAttempts(eventType, now.plusSeconds(2), 2);

        UUID inProgressId = seedPending(eventType, now.plusSeconds(3), 0);
        jdbcTemplate.update(
                "UPDATE outbox.outbox_event SET status = 'IN_PROGRESS', claimed_at = ?, claimed_by = ? WHERE id = ?",
                Timestamp.from(now), "instance-test", inProgressId);

        UUID deadLetteredId = seedPending(eventType, now.plusSeconds(4), 0);
        jdbcTemplate.update(
                "UPDATE outbox.outbox_event SET status = 'DEAD_LETTERED', dead_lettered_at = ? WHERE id = ?",
                Timestamp.from(now), deadLetteredId);

        UUID publishedId = seedPending(eventType, now.plusSeconds(5), 0);
        jdbcTemplate.update(
                "UPDATE outbox.outbox_event SET status = 'PUBLISHED', published_at = ? WHERE id = ?",
                Timestamp.from(now), publishedId);

        OutboxHealthSummary after = query.search(emptyCriteria()).health();

        assertThat(after.pendingCount() - before.pendingCount()).isEqualTo(2);
        assertThat(after.retryingCount() - before.retryingCount()).isEqualTo(1);
        assertThat(after.inProgressCount() - before.inProgressCount()).isEqualTo(1);
        assertThat(after.deadLetteredCount() - before.deadLetteredCount()).isEqualTo(1);
        assertThat(after.recentlyDeadLetteredCount() - before.recentlyDeadLetteredCount()).isEqualTo(1);
        assertThat(after.recentlyPublishedCount() - before.recentlyPublishedCount()).isEqualTo(1);
        assertThat(after.windowMinutes()).isEqualTo(OutboxOperatorQuery.WINDOW_MINUTES);
        assertThat(after.oldestPendingAgeSeconds()).isNotNull();
        assertThat(after.asOf()).isAfterOrEqualTo(before.asOf());
    }

    @Test
    void searchWithNoMatchesReturnsEmptyPage() {
        var result = query.search(criteria(uniqueEventType(), null, OutboxOperatorQuery.DEFAULT_PAGE_SIZE)).events();
        assertThat(result.records()).isEmpty();
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void rejectsMalformedCursor() {
        OutboxOperatorSearchCriteria malformed = criteria(uniqueEventType(), "not-a-valid-cursor!!", 10);
        assertThatThrownBy(() -> query.search(malformed)).isInstanceOf(IllegalArgumentException.class);
    }

    private UUID seedPending(String eventType, Instant occurredAt, int attemptCount) {
        UUID id = UUID.randomUUID();
        OutboxEventEntity entity =
                new OutboxEventEntity(id, "Test", "agg-" + id, eventType, "{}", occurredAt);
        for (int i = 0; i < attemptCount; i++) {
            entity.recordFailure("seed failure " + i, occurredAt);
        }
        repository.save(entity);
        return id;
    }

    private UUID seedWithAttempts(String eventType, Instant occurredAt, int attemptCount) {
        return seedPending(eventType, occurredAt, attemptCount);
    }

    private OutboxOperatorSearchCriteria criteria(String eventType, String cursor, int pageSize) {
        return new OutboxOperatorSearchCriteria(List.of(), eventType, null, null, null, null, cursor, pageSize);
    }

    private OutboxOperatorSearchCriteria emptyCriteria() {
        return new OutboxOperatorSearchCriteria(
                List.of(), null, null, null, null, null, null, OutboxOperatorQuery.DEFAULT_PAGE_SIZE);
    }

    private String uniqueEventType() {
        return "TEST_OUTBOX_OPERATOR_" + UUID.randomUUID();
    }
}
