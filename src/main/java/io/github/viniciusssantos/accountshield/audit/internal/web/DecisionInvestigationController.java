package io.github.viniciusssantos.accountshield.audit.internal.web;

import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionInvestigationCriteria;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionInvestigationPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    private final DecisionInvestigationQuery query;

    public DecisionInvestigationController(DecisionInvestigationQuery query) {
        this.query = query;
    }

    @PostMapping("/search")
    public ResponseEntity<DecisionSearchResponse> search(
            @Valid @RequestBody DecisionSearchRequest request) {
        DecisionInvestigationPage page = query.search(request.toCriteria());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(DecisionSearchResponse.from(page));
    }

    public record DecisionSearchRequest(
            @Pattern(regexp = CORRELATION_PATTERN) String correlationId,
            EventTypeFilter eventType,
            OutcomeFilter outcome,
            RiskBandFilter riskBand,
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
                    pageSize == null
                            ? DecisionInvestigationQuery.DEFAULT_PAGE_SIZE
                            : pageSize);
        }
    }

    public enum EventTypeFilter {
        LOGIN_ATTEMPT,
        SENSITIVE_ACTION,
        LOGIN_RECOVERY_ATTEMPT,
        PASSWORD_RESET_ATTEMPT,
        CREDENTIAL_CHANGE_ATTEMPT,
        DEVICE_TRUST_RESET_ATTEMPT
    }

    public enum OutcomeFilter {
        ALLOW,
        MONITOR,
        REQUIRE_STEP_UP,
        TEMPORARILY_BLOCK,
        START_RECOVERY
    }

    public enum RiskBandFilter {
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
                    page.decisions().stream()
                            .map(DecisionSummaryResponse::from)
                            .toList(),
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
}
