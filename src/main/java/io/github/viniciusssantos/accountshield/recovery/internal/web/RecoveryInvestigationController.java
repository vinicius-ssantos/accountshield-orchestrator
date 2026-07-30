package io.github.viniciusssantos.accountshield.recovery.internal.web;

import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowDetailQuery;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowDetailQuery.RecoveryChallengeSummary;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowDetailQuery.RecoveryFlowDetail;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowDetailQuery.SectionAvailability;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowSearchQuery;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowSearchQuery.RecoveryFlowSearchCriteria;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowSearchQuery.RecoveryFlowSearchPage;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
public class RecoveryInvestigationController {

    private static final String RECOVERY_REFERENCE_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}";

    private final RecoveryFlowSearchQuery searchQuery;
    private final RecoveryFlowDetailQuery detailQuery;

    public RecoveryInvestigationController(
            RecoveryFlowSearchQuery searchQuery,
            RecoveryFlowDetailQuery detailQuery) {
        this.searchQuery = searchQuery;
        this.detailQuery = detailQuery;
    }

    @Operation(
            operationId = "searchRecoveryInvestigations",
            summary = "Search the authorized privacy-minimized recovery investigation read model")
    @PostMapping("/search")
    public ResponseEntity<RecoverySearchResponse> search(
            @Valid @RequestBody RecoverySearchRequest request) {
        RecoveryFlowSearchPage page = searchQuery.search(request.toCriteria());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(RecoverySearchResponse.from(page));
    }

    @Operation(
            operationId = "investigateRecovery",
            summary = "Retrieve one authorized privacy-minimized recovery investigation detail")
    @PostMapping("/investigate")
    public ResponseEntity<RecoveryDetailResponse> investigate(
            @Valid @RequestBody RecoveryDetailRequest request) {
        RecoveryFlowDetail detail = detailQuery.investigate(request.recoveryReference())
                .orElseThrow(RecoveryInvestigationNotFoundException::new);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(RecoveryDetailResponse.from(detail));
    }

    public record RecoverySearchRequest(
            RecoverySearchStatus status,
            RecoverySearchClassification classification,
            RecoverySearchEventType eventType,
            Instant initiatedFrom,
            Instant initiatedTo,
            Instant eligibleBefore,
            Instant eligibleAfter,
            @Min(0) @Max(100) Integer minimumRiskScore,
            @Min(0) @Max(100) Integer maximumRiskScore,
            @Size(max = 256) String cursor,
            @Min(1) @Max(RecoveryFlowSearchQuery.MAX_PAGE_SIZE) Integer pageSize) {

        RecoveryFlowSearchCriteria toCriteria() {
            return new RecoveryFlowSearchCriteria(
                    status == null ? null : status.name(),
                    classification == null ? null : classification.name(),
                    eventType == null ? null : eventType.name(),
                    initiatedFrom,
                    initiatedTo,
                    eligibleBefore,
                    eligibleAfter,
                    minimumRiskScore,
                    maximumRiskScore,
                    cursor,
                    pageSize == null ? RecoveryFlowSearchQuery.DEFAULT_PAGE_SIZE : pageSize);
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

    public record RecoverySearchResponse(
            List<RecoverySummaryResponse> recoveries,
            String nextCursor,
            int pageSize,
            boolean hasMore) {

        static RecoverySearchResponse from(RecoveryFlowSearchPage page) {
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
            Instant eligibleAfter) {

        static RecoverySummaryResponse from(RecoveryFlowSearchQuery.RecoveryFlowSearchSummary summary) {
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
                    summary.eligibleAfter());
        }
    }

    public record RecoveryDetailRequest(
            @NotBlank
            @Size(max = 36)
            @Pattern(regexp = RECOVERY_REFERENCE_PATTERN)
            String recoveryReference) {
    }

    public record RecoveryDetailResponse(
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
            Instant terminalAt,
            String reviewer,
            String maskedOriginatingDecisionReference,
            String maskedProtectionRequestReference,
            List<RecoveryChallengeResponse> challenges,
            SectionAvailability challengeSection,
            boolean partial) {

        static RecoveryDetailResponse from(RecoveryFlowDetail detail) {
            return new RecoveryDetailResponse(
                    detail.recoveryReference(),
                    detail.maskedSubjectReference(),
                    detail.eventType(),
                    detail.status(),
                    detail.terminal(),
                    detail.classification(),
                    detail.classificationRuleVersion(),
                    detail.riskScore(),
                    detail.initiatedAt(),
                    detail.updatedAt(),
                    detail.eligibleAfter(),
                    detail.terminalAt(),
                    detail.reviewer(),
                    detail.maskedOriginatingDecisionReference(),
                    detail.maskedProtectionRequestReference(),
                    detail.challenges().stream().map(RecoveryChallengeResponse::from).toList(),
                    detail.challengeSection(),
                    detail.partial());
        }
    }

    public record RecoveryChallengeResponse(
            String reference,
            String challengeType,
            String purpose,
            String status,
            Instant createdAt,
            Instant expiresAt,
            Instant consumedAt) {

        static RecoveryChallengeResponse from(RecoveryChallengeSummary summary) {
            return new RecoveryChallengeResponse(
                    summary.reference(),
                    summary.challengeType(),
                    summary.purpose(),
                    summary.status(),
                    summary.createdAt(),
                    summary.expiresAt(),
                    summary.consumedAt());
        }
    }
}
