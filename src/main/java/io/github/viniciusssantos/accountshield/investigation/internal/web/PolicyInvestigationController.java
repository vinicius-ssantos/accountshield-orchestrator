package io.github.viniciusssantos.accountshield.investigation.internal.web;

import io.github.viniciusssantos.accountshield.investigation.PolicyInvestigationQuery;
import io.github.viniciusssantos.accountshield.investigation.PolicyInvestigationQuery.ImpactAvailability;
import io.github.viniciusssantos.accountshield.investigation.PolicyInvestigationQuery.ImpactSummary;
import io.github.viniciusssantos.accountshield.investigation.PolicyInvestigationQuery.MaskedDivergentDecision;
import io.github.viniciusssantos.accountshield.investigation.PolicyInvestigationQuery.PolicyInvestigationDetail;
import io.github.viniciusssantos.accountshield.investigation.PolicyInvestigationQuery.ReasonEvidence;
import io.github.viniciusssantos.accountshield.investigation.PolicyInvestigationQuery.RolloutSummary;
import io.github.viniciusssantos.accountshield.policy.PolicyDirectoryQuery.RoutingScopeEntry;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionSummary;
import io.github.viniciusssantos.accountshield.simulation.PolicySegmentImpact;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operator/policies")
public class PolicyInvestigationController {

    private final PolicyInvestigationQuery query;

    public PolicyInvestigationController(PolicyInvestigationQuery query) {
        this.query = query;
    }

    @Operation(
            operationId = "investigatePolicy",
            summary = "Retrieve one authorized privacy-minimized policy lifecycle and impact investigation")
    @PostMapping("/investigate")
    public ResponseEntity<PolicyInvestigationResponse> investigate(
            @Valid @RequestBody PolicyInvestigationRequest request) {
        PolicyInvestigationDetail detail = query.investigate(request.policyKey())
                .orElseThrow(PolicyInvestigationNotFoundException::new);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(PolicyInvestigationResponse.from(detail));
    }

    public record PolicyInvestigationRequest(
            @NotBlank @Size(max = 100) String policyKey) {
    }

    public record RoutingScopeResponse(String clientId, String eventType) {
        static RoutingScopeResponse from(RoutingScopeEntry entry) {
            return new RoutingScopeResponse(entry.clientId(), entry.eventType());
        }
    }

    public record RolloutSummaryResponse(
            String candidateVersion,
            int rolloutPercentage,
            String status,
            Instant startedAt,
            String startedBy,
            Instant updatedAt,
            Instant rolledBackAt,
            String rolledBackBy) {
        static RolloutSummaryResponse from(RolloutSummary summary) {
            return new RolloutSummaryResponse(
                    summary.candidateVersion(),
                    summary.rolloutPercentage(),
                    summary.status(),
                    summary.startedAt(),
                    summary.startedBy(),
                    summary.updatedAt(),
                    summary.rolledBackAt(),
                    summary.rolledBackBy());
        }
    }

    public record ReasonResponse(String code, int contribution) {
        static ReasonResponse from(ReasonEvidence reason) {
            return new ReasonResponse(reason.code(), reason.contribution());
        }
    }

    public record DivergentDecisionResponse(
            String maskedProtectionRequestReference,
            String redactedAccountReference,
            String originalOutcome,
            String candidateOutcome,
            int riskScore,
            List<ReasonResponse> originalReasons) {
        static DivergentDecisionResponse from(MaskedDivergentDecision decision) {
            return new DivergentDecisionResponse(
                    decision.maskedProtectionRequestReference(),
                    decision.redactedAccountReference(),
                    decision.originalOutcome(),
                    decision.candidateOutcome(),
                    decision.riskScore(),
                    decision.originalReasons().stream().map(ReasonResponse::from).toList());
        }
    }

    public record ImpactSummaryResponse(
            String candidatePolicyVersion,
            Set<String> originalPolicyVersionsObserved,
            Set<String> algorithmVersionsObserved,
            int totalDecisions,
            int divergentDecisionsCount,
            double divergencePercentage,
            double maxDivergencePercentageThreshold,
            boolean exceedsDivergenceThreshold,
            Map<String, Map<String, Long>> transitionMatrix,
            Map<String, PolicySegmentImpact> impactByEventType,
            Map<String, PolicySegmentImpact> impactByRiskBand,
            List<DivergentDecisionResponse> divergentDecisions) {
        static ImpactSummaryResponse from(ImpactSummary summary) {
            return new ImpactSummaryResponse(
                    summary.candidatePolicyVersion(),
                    summary.originalPolicyVersionsObserved(),
                    summary.algorithmVersionsObserved(),
                    summary.totalDecisions(),
                    summary.divergentDecisionsCount(),
                    summary.divergencePercentage(),
                    summary.maxDivergencePercentageThreshold(),
                    summary.exceedsDivergenceThreshold(),
                    summary.transitionMatrix(),
                    summary.impactByEventType(),
                    summary.impactByRiskBand(),
                    summary.divergentDecisions().stream().map(DivergentDecisionResponse::from).toList());
        }
    }

    public record PolicyInvestigationResponse(
            String policyKey,
            List<PolicyVersionSummary> versions,
            List<RoutingScopeResponse> routingScope,
            RolloutSummaryResponse activeRollout,
            ImpactSummaryResponse impactAnalysis,
            ImpactAvailability impactAvailability) {

        static PolicyInvestigationResponse from(PolicyInvestigationDetail detail) {
            return new PolicyInvestigationResponse(
                    detail.policyKey(),
                    detail.versions(),
                    detail.routingScope().stream().map(RoutingScopeResponse::from).toList(),
                    detail.activeRollout() == null ? null : RolloutSummaryResponse.from(detail.activeRollout()),
                    detail.impactAnalysis() == null ? null : ImpactSummaryResponse.from(detail.impactAnalysis()),
                    detail.impactAvailability());
        }
    }
}
