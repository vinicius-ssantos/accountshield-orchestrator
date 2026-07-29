package io.github.viniciusssantos.accountshield.audit.internal.web;

import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.ChallengeSummary;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionInvestigationCriteria;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionInvestigationDetail;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionInvestigationPage;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionReasonSummary;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionTimelineEntry;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.ExecutionProvenanceSummary;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.InvestigationSections;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.OutboxSummary;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.PolicyProvenanceSummary;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.RecoverySummary;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.SignalProvenanceSummary;
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
@RequestMapping("/api/v1/operator/decisions")
public class DecisionInvestigationController {

    private static final String CORRELATION_PATTERN = "[A-Za-z0-9._-]{1,128}";
    private static final String DECISION_REFERENCE_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}";

    private final DecisionInvestigationQuery query;

    public DecisionInvestigationController(DecisionInvestigationQuery query) {
        this.query = query;
    }

    @Operation(
            operationId = "searchDecisionInvestigations",
            summary = "Search the authorized privacy-minimized decision investigation read model")
    @PostMapping("/search")
    public ResponseEntity<DecisionSearchResponse> search(
            @Valid @RequestBody DecisionSearchRequest request) {
        DecisionInvestigationPage page = query.search(request.toCriteria());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(DecisionSearchResponse.from(page));
    }

    @Operation(
            operationId = "investigateDecision",
            summary = "Retrieve one authorized privacy-minimized decision timeline and provenance view")
    @PostMapping("/investigate")
    public ResponseEntity<DecisionInvestigationResponse> investigate(
            @Valid @RequestBody DecisionInvestigationRequest request) {
        DecisionInvestigationDetail detail = query.investigate(request.decisionReference())
                .orElseThrow(DecisionInvestigationNotFoundException::new);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(DecisionInvestigationResponse.from(detail));
    }

    public record DecisionInvestigationRequest(
            @NotBlank
            @Size(max = 36)
            @Pattern(regexp = DECISION_REFERENCE_PATTERN)
            String decisionReference) {
    }

    public record DecisionSearchRequest(
            @Pattern(regexp = CORRELATION_PATTERN) String correlationId,
            DecisionSearchEventType eventType,
            DecisionSearchOutcome outcome,
            DecisionSearchRiskBand riskBand,
            @Size(max = 40) String policyVersion,
            Instant decidedFrom,
            Instant decidedTo,
            @Size(max = 256) String cursor,
            @Min(1) @Max(DecisionInvestigationQuery.MAX_PAGE_SIZE) Integer pageSize) {

        DecisionInvestigationCriteria toCriteria() {
            return new DecisionInvestigationCriteria(
                    correlationId,
                    eventType == null ? null : eventType.name(),
                    outcome == null ? null : outcome.name(),
                    riskBand == null ? null : riskBand.name(),
                    policyVersion,
                    decidedFrom,
                    decidedTo,
                    cursor,
                    pageSize == null ? DecisionInvestigationQuery.DEFAULT_PAGE_SIZE : pageSize);
        }
    }

    public enum DecisionSearchEventType {
        LOGIN_ATTEMPT,
        SENSITIVE_ACTION,
        LOGIN_RECOVERY_ATTEMPT,
        PASSWORD_RESET_ATTEMPT,
        CREDENTIAL_CHANGE_ATTEMPT,
        DEVICE_TRUST_RESET_ATTEMPT
    }

    public enum DecisionSearchOutcome {
        ALLOW,
        REQUIRE_STEP_UP,
        START_RECOVERY,
        TEMPORARILY_BLOCK
    }

    public enum DecisionSearchRiskBand {
        LOW,
        MEDIUM,
        HIGH
    }

    public record DecisionSearchResponse(
            List<DecisionSummaryResponse> decisions,
            String nextCursor,
            int pageSize,
            boolean hasMore) {

        static DecisionSearchResponse from(DecisionInvestigationPage page) {
            return new DecisionSearchResponse(
                    page.decisions().stream().map(DecisionSummaryResponse::from).toList(),
                    page.nextCursor(),
                    page.pageSize(),
                    page.hasMore());
        }
    }

    public record DecisionSummaryResponse(
            String decisionReference,
            String correlationId,
            String eventType,
            String outcome,
            int riskScore,
            String riskBand,
            String policyKey,
            String policyVersion,
            Instant decidedAt,
            boolean degraded,
            boolean simulated,
            boolean provenanceAvailable) {

        static DecisionSummaryResponse from(
                DecisionInvestigationQuery.DecisionInvestigationSummary summary) {
            return new DecisionSummaryResponse(
                    summary.decisionReference(),
                    summary.correlationId(),
                    summary.eventType(),
                    summary.outcome(),
                    summary.riskScore(),
                    summary.riskBand(),
                    summary.policyKey(),
                    summary.policyVersion(),
                    summary.decidedAt(),
                    summary.degraded(),
                    summary.simulated(),
                    summary.provenanceAvailable());
        }
    }

    public record DecisionInvestigationResponse(
            DecisionSummaryResponse decision,
            String maskedSubjectReference,
            List<DecisionReasonSummary> reasons,
            SignalProvenanceSummary signalProvenance,
            PolicyProvenanceSummary policyProvenance,
            ExecutionProvenanceSummary executionProvenance,
            List<ChallengeSummary> challenges,
            RecoverySummary recovery,
            List<OutboxSummary> outboxEvents,
            List<DecisionTimelineEntry> timeline,
            InvestigationSections sections,
            boolean partial) {

        static DecisionInvestigationResponse from(DecisionInvestigationDetail detail) {
            return new DecisionInvestigationResponse(
                    DecisionSummaryResponse.from(detail.decision()),
                    detail.maskedSubjectReference(),
                    detail.reasons(),
                    detail.signalProvenance(),
                    detail.policyProvenance(),
                    detail.executionProvenance(),
                    detail.challenges(),
                    detail.recovery(),
                    detail.outboxEvents(),
                    detail.timeline(),
                    detail.sections(),
                    detail.partial());
        }
    }
}
