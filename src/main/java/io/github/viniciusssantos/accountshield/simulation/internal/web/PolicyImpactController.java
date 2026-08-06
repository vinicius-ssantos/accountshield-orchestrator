package io.github.viniciusssantos.accountshield.simulation.internal.web;

import io.github.viniciusssantos.accountshield.simulation.PolicyImpactAnalysisService;
import io.github.viniciusssantos.accountshield.simulation.PolicyImpactReport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/simulation")
class PolicyImpactController {

    private final PolicyImpactAnalysisService policyImpactAnalysisService;

    PolicyImpactController(PolicyImpactAnalysisService policyImpactAnalysisService) {
        this.policyImpactAnalysisService = policyImpactAnalysisService;
    }

    @PostMapping("/policy-impact")
    public ResponseEntity<PolicyImpactReport> analyzeImpact(
            @RequestParam String policyKey,
            @RequestParam String candidatePolicyVersion,
            @RequestParam(defaultValue = "5000") int maxSamples) {
        return ResponseEntity.ok(
                policyImpactAnalysisService.analyzeImpact(policyKey, candidatePolicyVersion, maxSamples));
    }
}
