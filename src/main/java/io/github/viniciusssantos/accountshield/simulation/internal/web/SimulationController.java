package io.github.viniciusssantos.accountshield.simulation.internal.web;

import io.github.viniciusssantos.accountshield.risk.RiskReason;
import io.github.viniciusssantos.accountshield.simulation.ReplayResult;
import io.github.viniciusssantos.accountshield.simulation.ShadowEvaluationResult;
import io.github.viniciusssantos.accountshield.simulation.SimulationService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/simulation")
class SimulationController {

    private final SimulationService simulationService;

    SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @GetMapping("/replay/{protectionRequestId}")
    public ResponseEntity<ReplayResponse> replay(@PathVariable UUID protectionRequestId) {
        return simulationService.replay(protectionRequestId)
                .map(r -> ResponseEntity.ok(ReplayResponse.from(r)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/shadow")
    public ResponseEntity<ShadowResponse> shadow(
            @RequestParam String policyKey,
            @RequestParam int riskScore,
            @RequestParam String candidatePolicyVersion) {
        ShadowEvaluationResult result = simulationService.evaluateShadow(
                policyKey, riskScore, candidatePolicyVersion);
        return ResponseEntity.ok(ShadowResponse.from(result));
    }

    record ReplayResponse(
            UUID protectionRequestId,
            boolean matches,
            String originalOutcome,
            String replayedOutcome,
            int originalRiskScore,
            int replayedRiskScore,
            String originalRiskBand,
            String replayedRiskBand,
            List<RiskReason> originalReasons,
            List<RiskReason> replayedReasons,
            String policyKey,
            String policyVersion,
            String algorithmVersion,
            String normalizedInputSchemaVersion,
            String reasonCatalogVersion,
            String decisionEngineVersion,
            List<String> mismatches) {

        static ReplayResponse from(ReplayResult r) {
            return new ReplayResponse(
                    r.protectionRequestId(),
                    r.matches(),
                    r.originalOutcome(),
                    r.replayedOutcome(),
                    r.originalRiskScore(),
                    r.replayedRiskScore(),
                    r.originalRiskBand().name(),
                    r.replayedRiskBand().name(),
                    r.originalReasons(),
                    r.replayedReasons(),
                    r.policyKey(),
                    r.policyVersion(),
                    r.algorithmVersion(),
                    r.normalizedInputSchemaVersion(),
                    r.reasonCatalogVersion(),
                    r.decisionEngineVersion(),
                    r.mismatches());
        }
    }

    record ShadowResponse(
            String liveOutcome,
            String shadowOutcome,
            String livePolicyVersion,
            String shadowPolicyVersion,
            int riskScore,
            boolean diverged) {

        static ShadowResponse from(ShadowEvaluationResult r) {
            return new ShadowResponse(
                    r.liveOutcome(),
                    r.shadowOutcome(),
                    r.livePolicyVersion(),
                    r.shadowPolicyVersion(),
                    r.riskScore(),
                    r.diverged());
        }
    }
}
