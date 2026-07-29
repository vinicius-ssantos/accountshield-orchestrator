package io.github.viniciusssantos.accountshield.investigation.internal.web;

import io.github.viniciusssantos.accountshield.audit.DecisionEvidenceQuery;
import io.github.viniciusssantos.accountshield.investigation.DecisionTimelineQuery;
import io.github.viniciusssantos.accountshield.investigation.DecisionTimelineQuery.DecisionTimeline;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
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
public class DecisionTimelineController {

    private static final String DECISION_REFERENCE_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}";

    private final DecisionTimelineQuery query;

    public DecisionTimelineController(DecisionTimelineQuery query) {
        this.query = query;
    }

    @Operation(
            operationId = "investigateDecision",
            summary = "Retrieve one authorized privacy-minimized decision timeline and provenance view")
    @PostMapping("/investigate")
    public ResponseEntity<DecisionTimelineResponse> investigate(
            @Valid @RequestBody DecisionTimelineRequest request) {
        DecisionTimeline timeline = query.investigate(request.decisionReference())
                .orElseThrow(DecisionTimelineNotFoundException::new);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(DecisionTimelineResponse.from(timeline));
    }

    public record DecisionTimelineRequest(
            @NotBlank
            @Size(max = 36)
            @Pattern(regexp = DECISION_REFERENCE_PATTERN)
            String decisionReference) {
    }

    public record DecisionTimelineResponse(
            DecisionSummaryResponse decision,
            String maskedSubjectReference,
            List<DecisionEvidenceQuery.ReasonEvidence> reasons,
            DecisionEvidenceQuery.SignalProvenanceEvidence signalProvenance,
            DecisionEvidenceQuery.PolicyProvenanceEvidence policyProvenance,
            DecisionEvidenceQuery.ExecutionProvenanceEvidence executionProvenance,
            List<DecisionTimelineQuery.ChallengeSummary> challenges,
            DecisionTimelineQuery.RecoverySummary recovery,
            List<DecisionTimelineQuery.OutboxSummary> outboxEvents,
            List<DecisionTimelineQuery.TimelineEntry> timeline,
            DecisionTimelineQuery.InvestigationSections sections,
            boolean partial) {

        static DecisionTimelineResponse from(DecisionTimeline value) {
            var evidence = value.evidence();
            return new DecisionTimelineResponse(
                    DecisionSummaryResponse.from(evidence.decision()),
                    evidence.maskedSubjectReference(),
                    evidence.reasons(),
                    evidence.signalProvenance(),
                    evidence.policyProvenance(),
                    evidence.executionProvenance(),
                    value.challenges(),
                    value.recovery(),
                    value.outboxEvents(),
                    value.timeline(),
                    value.sections(),
                    value.partial());
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

        static DecisionSummaryResponse from(DecisionEvidenceQuery.DecisionEvidenceSummary summary) {
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
