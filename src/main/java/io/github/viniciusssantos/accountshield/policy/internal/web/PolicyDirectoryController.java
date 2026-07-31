package io.github.viniciusssantos.accountshield.policy.internal.web;

import io.github.viniciusssantos.accountshield.policy.PolicyDirectoryQuery;
import io.github.viniciusssantos.accountshield.policy.PolicyDirectoryQuery.PolicySummary;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operator/policies")
public class PolicyDirectoryController {

    private final PolicyDirectoryQuery query;

    public PolicyDirectoryController(PolicyDirectoryQuery query) {
        this.query = query;
    }

    @Operation(
            operationId = "searchPolicyDirectory",
            summary = "Search the authorized privacy-minimized policy directory read model")
    @PostMapping("/search")
    public ResponseEntity<PolicyDirectorySearchResponse> search() {
        List<PolicySummary> summaries = query.search();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new PolicyDirectorySearchResponse(
                        summaries.stream().map(PolicyDirectorySummaryResponse::from).toList()));
    }

    public record PolicyDirectorySummaryResponse(
            String policyKey,
            int totalVersions,
            String activeVersion,
            Instant activeVersionActivatedAt,
            boolean hasActiveRollout) {

        static PolicyDirectorySummaryResponse from(PolicySummary summary) {
            return new PolicyDirectorySummaryResponse(
                    summary.policyKey(),
                    summary.totalVersions(),
                    summary.activeVersion(),
                    summary.activeVersionActivatedAt(),
                    summary.hasActiveRollout());
        }
    }

    public record PolicyDirectorySearchResponse(List<PolicyDirectorySummaryResponse> policies) {
    }
}
