package io.github.viniciusssantos.accountshield.recovery.internal.web;

import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.RecoveryCriteria;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.RecoveryPage;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.RecoverySummary;
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
@RequestMapping("/api/v1/operator/recoveries")
public class RecoverySearchController {

    private final RecoveryOperationsQuery query;

    public RecoverySearchController(RecoveryOperationsQuery query) {
        this.query = query;
    }

    @Operation(
            operationId = "searchRecoveryInvestigations",
            summary = "Search the authorized privacy-minimized recovery investigation read model")
    @PostMapping("/search")
    public ResponseEntity<RecoverySearchResponse> search(
            @Valid @RequestBody RecoverySearchRequest request) {
        RecoveryPage page = query.search(request.toCriteria());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(RecoverySearchResponse.from(page));
    }

    public record RecoverySearchRequest(
            RecoverySearchStatus status,
            RecoverySearchClassification classification,
            RecoverySearchEventType eventType,
            RecoveryReviewState reviewState,
            Instant initiatedFrom,
            Instant initiatedTo,
            Instant eligibleFrom,
            Instant eligibleTo,
            @Min(0) @Max(100) Integer minimumRiskScore,
            @Min(0) @Max(100) Integer maximumRiskScore,
            @Size(max = 256) String cursor,
            @Min(1) @Max(RecoveryOperationsQuery.MAX_PAGE_SIZE) Integer pageSize) {

        RecoveryCriteria toCriteria() {
            return new RecoveryCriteria(
                    status == null ? null : status.name(),
                    classification == null ? null : classification.name(),
                    eventType == null ? null : eventType.name(),
                    reviewState == null ? null : reviewState.name(),
                    initiatedFrom,
                    initiatedTo,
                    eligibleFrom,
                    eligibleTo,
                    minimumRiskScore,
                    maximumRiskScore,
                    cursor,
                    pageSize == null ? RecoveryOperationsQuery.DEFAULT_PAGE_SIZE : pageSize);
        }
    }

    public enum RecoverySearchStatus {
        INITIATED,
        VERIFYING_IDENTITY,
        IDENTITY_VERIFIED,
        DELAYED,
        MANUAL_REVIEW,
        COMPLETED,
        IDENTITY_FAILED,
        REJECTED,
        ABORTED
    }

    public enum RecoverySearchClassification {
        IMMEDIATE,
        DELAYED,
        MANUAL_REVIEW
    }

    public enum RecoverySearchEventType {
        LOGIN,
        PASSWORD_RESET,
        CREDENTIAL_CHANGE,
        DEVICE_TRUST_RESET
    }

    public enum RecoveryReviewState {
        PENDING,
        REVIEWED,
        NOT_APPLICABLE
    }

    public record RecoverySearchResponse(
            List<RecoverySummaryResponse> recoveries,
            String nextCursor,
            int pageSize,
            boolean hasMore) {

        static RecoverySearchResponse from(RecoveryPage page) {
            return new RecoverySearchResponse(
                    page.recoveries().stream().map(RecoverySummaryResponse::from).toList(),
                    page.nextCursor(),
                    page.pageSize(),
                    page.hasMore());
        }
    }

    public record RecoverySummaryResponse(
            String recoveryReference,
            String maskedSubjectReference,
            String eventType,
            String status,
            boolean terminal,
            String classification,
            String classificationRuleVersion,
            int riskScore,
            Instant initiatedAt,
            Instant updatedAt,
            Instant eligibleAfter,
            String originatingDecisionReference,
            String reviewState,
            boolean challengeExpected) {

        static RecoverySummaryResponse from(RecoverySummary summary) {
            return new RecoverySummaryResponse(
                    summary.recoveryReference(),
                    summary.maskedSubjectReference(),
                    summary.eventType(),
                    summary.status(),
                    summary.terminal(),
                    summary.classification(),
                    summary.classificationRuleVersion(),
                    summary.riskScore(),
                    summary.initiatedAt(),
                    summary.updatedAt(),
                    summary.eligibleAfter(),
                    summary.originatingDecisionReference(),
                    summary.reviewState(),
                    summary.challengeExpected());
        }
    }
}
