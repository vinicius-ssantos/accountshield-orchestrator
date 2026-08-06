package io.github.viniciusssantos.accountshield.outbox.internal.web;

import io.github.viniciusssantos.accountshield.outbox.OutboxOperatorQuery;
import io.github.viniciusssantos.accountshield.outbox.OutboxOperatorQuery.OutboxHealthSummary;
import io.github.viniciusssantos.accountshield.outbox.OutboxOperatorQuery.OutboxOperatorEventRecord;
import io.github.viniciusssantos.accountshield.outbox.OutboxOperatorQuery.OutboxOperatorSearchCriteria;
import io.github.viniciusssantos.accountshield.outbox.OutboxOperatorQuery.OutboxOperatorSearchResult;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operator/outbox")
public class OutboxOperatorController {

    private final OutboxOperatorQuery query;

    public OutboxOperatorController(OutboxOperatorQuery query) {
        this.query = query;
    }

    @Operation(
            operationId = "searchOutboxOperations",
            summary = "Search the authorized privacy-minimized outbox delivery health and event read model")
    @PostMapping("/search")
    public ResponseEntity<OutboxSearchResponse> search(@Valid @RequestBody OutboxSearchRequest request) {
        OutboxOperatorSearchResult result = query.search(request.toCriteria());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(OutboxSearchResponse.from(result));
    }

    public record OutboxSearchRequest(
            List<OutboxSearchStatus> statuses,
            @Size(max = 160) String eventType,
            Instant occurredFrom,
            Instant occurredTo,
            @Min(0) @Max(OutboxOperatorQuery.MAX_ATTEMPT_COUNT_BOUND) Integer minAttemptCount,
            @Min(0) @Max(OutboxOperatorQuery.MAX_ATTEMPT_COUNT_BOUND) Integer maxAttemptCount,
            @Size(max = 256) String cursor,
            @Min(1) @Max(OutboxOperatorQuery.MAX_PAGE_SIZE) Integer pageSize) {

        OutboxOperatorSearchCriteria toCriteria() {
            return new OutboxOperatorSearchCriteria(
                    statuses == null ? List.of() : statuses.stream().map(Enum::name).toList(),
                    eventType,
                    occurredFrom,
                    occurredTo,
                    minAttemptCount,
                    maxAttemptCount,
                    cursor,
                    pageSize == null ? OutboxOperatorQuery.DEFAULT_PAGE_SIZE : pageSize);
        }
    }

    public enum OutboxSearchStatus {
        PENDING,
        IN_PROGRESS,
        PUBLISHED,
        DEAD_LETTERED
    }

    public record OutboxSearchResponse(OutboxHealthResponse health, OutboxEventPageResponse events) {

        static OutboxSearchResponse from(OutboxOperatorSearchResult result) {
            return new OutboxSearchResponse(
                    OutboxHealthResponse.from(result.health()), OutboxEventPageResponse.from(result.events()));
        }
    }

    public record OutboxHealthResponse(
            long pendingCount,
            long retryingCount,
            long inProgressCount,
            long deadLetteredCount,
            Double oldestPendingAgeSeconds,
            long recentlyDeadLetteredCount,
            long recentlyPublishedCount,
            int windowMinutes,
            Instant asOf) {

        static OutboxHealthResponse from(OutboxHealthSummary summary) {
            return new OutboxHealthResponse(
                    summary.pendingCount(),
                    summary.retryingCount(),
                    summary.inProgressCount(),
                    summary.deadLetteredCount(),
                    summary.oldestPendingAgeSeconds(),
                    summary.recentlyDeadLetteredCount(),
                    summary.recentlyPublishedCount(),
                    summary.windowMinutes(),
                    summary.asOf());
        }
    }

    public record OutboxEventPageResponse(
            List<OutboxEventRecordResponse> records, String nextCursor, int pageSize, boolean hasMore) {

        static OutboxEventPageResponse from(OutboxOperatorQuery.OutboxOperatorEventPage page) {
            return new OutboxEventPageResponse(
                    page.records().stream().map(OutboxEventRecordResponse::from).toList(),
                    page.nextCursor(),
                    page.pageSize(),
                    page.hasMore());
        }
    }

    public record OutboxEventRecordResponse(
            String eventId,
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

        static OutboxEventRecordResponse from(OutboxOperatorEventRecord record) {
            return new OutboxEventRecordResponse(
                    record.eventId().toString(),
                    record.aggregateType(),
                    record.eventType(),
                    record.status(),
                    record.attemptCount(),
                    record.occurredAt(),
                    record.publishedAt(),
                    record.deadLetteredAt(),
                    record.nextAttemptAt(),
                    record.claimed(),
                    record.claimedAt(),
                    record.schemaVersion(),
                    record.maskedCorrelationReference(),
                    record.deadLetterReasonAvailable(),
                    record.deadLetterFailureCategory());
        }
    }
}
