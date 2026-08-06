package io.github.viniciusssantos.accountshield.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Privacy-minimized read port for outbox delivery health and dead-letter search, for the
 * security-operations console.
 *
 * <p>This contract deliberately does not expose the raw JSON {@code payload}, the raw {@code
 * last_error} exception text, or the claiming worker's identity ({@code claimed_by}).</p>
 */
public interface OutboxOperatorQuery {

    int DEFAULT_PAGE_SIZE = 25;
    int MAX_PAGE_SIZE = 100;
    int MAX_ATTEMPT_COUNT_BOUND = 1000;
    int WINDOW_MINUTES = 15;
    Duration MAX_TIME_WINDOW = Duration.ofDays(31);
    Set<String> VALID_STATUSES = Set.of("PENDING", "IN_PROGRESS", "PUBLISHED", "DEAD_LETTERED");

    OutboxOperatorSearchResult search(OutboxOperatorSearchCriteria criteria);

    record OutboxOperatorSearchCriteria(
            List<String> statuses,
            String eventType,
            Instant occurredFrom,
            Instant occurredTo,
            Integer minAttemptCount,
            Integer maxAttemptCount,
            String cursor,
            int pageSize) {

        public OutboxOperatorSearchCriteria {
            statuses = statuses == null ? List.of() : List.copyOf(statuses);
            for (String status : statuses) {
                if (!VALID_STATUSES.contains(status)) {
                    throw new IllegalArgumentException("unsupported outbox status: " + status);
                }
            }
            eventType = optionalBounded(eventType, "eventType", 160);
            cursor = optionalBounded(cursor, "cursor", 256);
            if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
            }
            if (minAttemptCount != null && (minAttemptCount < 0 || minAttemptCount > MAX_ATTEMPT_COUNT_BOUND)) {
                throw new IllegalArgumentException(
                        "minAttemptCount must be between 0 and " + MAX_ATTEMPT_COUNT_BOUND);
            }
            if (maxAttemptCount != null && (maxAttemptCount < 0 || maxAttemptCount > MAX_ATTEMPT_COUNT_BOUND)) {
                throw new IllegalArgumentException(
                        "maxAttemptCount must be between 0 and " + MAX_ATTEMPT_COUNT_BOUND);
            }
            if (minAttemptCount != null && maxAttemptCount != null && minAttemptCount > maxAttemptCount) {
                throw new IllegalArgumentException("minAttemptCount must not exceed maxAttemptCount");
            }
            if (occurredFrom != null && occurredTo != null) {
                if (!occurredFrom.isBefore(occurredTo)) {
                    throw new IllegalArgumentException("occurredFrom must be before occurredTo");
                }
                if (Duration.between(occurredFrom, occurredTo).compareTo(MAX_TIME_WINDOW) > 0) {
                    throw new IllegalArgumentException(
                            "outbox search time window must not exceed " + MAX_TIME_WINDOW.toDays() + " days");
                }
            }
        }

        private static String optionalBounded(String value, String name, int maxLength) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim();
            if (normalized.isEmpty() || normalized.length() > maxLength) {
                throw new IllegalArgumentException(
                        name + " must contain between 1 and " + maxLength + " characters when provided");
            }
            return normalized;
        }
    }

    record OutboxHealthSummary(
            long pendingCount,
            long retryingCount,
            long inProgressCount,
            long deadLetteredCount,
            Double oldestPendingAgeSeconds,
            long recentlyDeadLetteredCount,
            long recentlyPublishedCount,
            int windowMinutes,
            Instant asOf) {

        public OutboxHealthSummary {
            Objects.requireNonNull(asOf, "asOf must not be null");
        }
    }

    record OutboxOperatorEventRecord(
            UUID eventId,
            String aggregateType,
            String eventType,
            String status,
            int attemptCount,
            Instant occurredAt,
            Instant publishedAt,
            Instant deadLetteredAt,
            Instant nextAttemptAt,
            boolean claimed,
            Instant claimedAt,
            String schemaVersion,
            String maskedCorrelationReference,
            boolean deadLetterReasonAvailable,
            String deadLetterFailureCategory) {

        public OutboxOperatorEventRecord {
            Objects.requireNonNull(eventId, "eventId must not be null");
            Objects.requireNonNull(aggregateType, "aggregateType must not be null");
            Objects.requireNonNull(eventType, "eventType must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            Objects.requireNonNull(maskedCorrelationReference, "maskedCorrelationReference must not be null");
        }
    }

    record OutboxOperatorEventPage(
            List<OutboxOperatorEventRecord> records,
            String nextCursor,
            int pageSize,
            boolean hasMore) {

        public OutboxOperatorEventPage {
            records = List.copyOf(Objects.requireNonNull(records, "records must not be null"));
            if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
            }
            if (hasMore && (nextCursor == null || nextCursor.isBlank())) {
                throw new IllegalArgumentException("nextCursor is required when hasMore is true");
            }
            if (!hasMore) {
                nextCursor = null;
            }
        }
    }

    record OutboxOperatorSearchResult(OutboxHealthSummary health, OutboxOperatorEventPage events) {

        public OutboxOperatorSearchResult {
            Objects.requireNonNull(health, "health must not be null");
            Objects.requireNonNull(events, "events must not be null");
        }
    }
}
