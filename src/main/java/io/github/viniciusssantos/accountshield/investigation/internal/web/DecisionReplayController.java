package io.github.viniciusssantos.accountshield.investigation.internal.web;

import io.github.viniciusssantos.accountshield.investigation.DecisionReplayQuery;
import io.github.viniciusssantos.accountshield.investigation.DecisionReplayQuery.DecisionReplayComparison;
import io.github.viniciusssantos.accountshield.investigation.DecisionReplayQuery.DecisionReplaySide;
import io.github.viniciusssantos.accountshield.investigation.DecisionReplayQuery.ReasonEvidence;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operator/decisions")
public class DecisionReplayController {

    private static final String DECISION_REFERENCE_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}";

    private final DecisionReplayQuery query;

    public DecisionReplayController(DecisionReplayQuery query) {
        this.query = query;
    }

    @Operation(
            operationId = "replayDecision",
            summary = "Retrieve one authorized privacy-minimized deterministic replay comparison")
    @PostMapping("/replay")
    public ResponseEntity<DecisionReplayResponse> replay(
            @Valid @RequestBody DecisionReplayRequest request) {
        DecisionReplayComparison comparison = query.replay(request.decisionReference())
                .orElseThrow(DecisionReplayNotFoundException::new);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(DecisionReplayResponse.from(comparison));
    }

    public record DecisionReplayRequest(
            @NotBlank
            @Size(max = 36)
            @Pattern(regexp = DECISION_REFERENCE_PATTERN)
            String decisionReference) {
    }

    public record ReasonResponse(String code, int contribution) {
        static ReasonResponse from(ReasonEvidence reason) {
            return new ReasonResponse(reason.code(), reason.contribution());
        }
    }

    public record DecisionReplaySideResponse(
            String outcome,
            int riskScore,
            String riskBand,
            List<ReasonResponse> reasons) {

        static DecisionReplaySideResponse from(DecisionReplaySide side) {
            return new DecisionReplaySideResponse(
                    side.outcome(),
                    side.riskScore(),
                    side.riskBand(),
                    side.reasons().stream().map(ReasonResponse::from).toList());
        }
    }

    public record DecisionReplayResponse(
            String decisionReference,
            String maskedSubjectReference,
            boolean matches,
            DecisionReplaySideResponse original,
            DecisionReplaySideResponse replayed,
            String policyKey,
            String policyVersion,
            String algorithmVersion,
            String normalizedInputSchemaVersion,
            String reasonCatalogVersion,
            String decisionEngineVersion,
            List<String> mismatches) {

        static DecisionReplayResponse from(DecisionReplayComparison comparison) {
            return new DecisionReplayResponse(
                    comparison.decisionReference(),
                    comparison.maskedSubjectReference(),
                    comparison.matches(),
                    DecisionReplaySideResponse.from(comparison.original()),
                    DecisionReplaySideResponse.from(comparison.replayed()),
                    comparison.policyKey(),
                    comparison.policyVersion(),
                    comparison.algorithmVersion(),
                    comparison.normalizedInputSchemaVersion(),
                    comparison.reasonCatalogVersion(),
                    comparison.decisionEngineVersion(),
                    comparison.mismatches());
        }
    }
}
