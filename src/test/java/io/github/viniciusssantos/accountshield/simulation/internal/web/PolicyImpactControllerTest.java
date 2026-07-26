package io.github.viniciusssantos.accountshield.simulation.internal.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.viniciusssantos.accountshield.policy.ActivePolicyUnavailableException;
import io.github.viniciusssantos.accountshield.simulation.PolicyImpactAnalysisService;
import io.github.viniciusssantos.accountshield.simulation.PolicyImpactReport;
import io.github.viniciusssantos.accountshield.simulation.PolicySegmentImpact;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PolicyImpactControllerTest {

    private final PolicyImpactAnalysisService policyImpactAnalysisService = mock(PolicyImpactAnalysisService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PolicyImpactController(policyImpactAnalysisService))
                .setControllerAdvice(new SimulationProblemHandler())
                .build();
    }

    @Test
    void analyzeImpactReturnsReport() throws Exception {
        PolicyImpactReport report = new PolicyImpactReport(
                "account-protection-default", "2.0.0",
                Set.of("1.0.0"), Set.of("risk-rules-1.0"),
                10, 2, 20.0, 20.0, false,
                Map.of("ALLOW", Map.of("ALLOW", 8L, "REQUIRE_STEP_UP", 2L)),
                Map.of("LOGIN_ATTEMPT", new PolicySegmentImpact("LOGIN_ATTEMPT", 10, 2)),
                Map.of("LOW", new PolicySegmentImpact("LOW", 10, 2)),
                List.of());
        when(policyImpactAnalysisService.analyzeImpact("account-protection-default", "2.0.0", 500))
                .thenReturn(report);

        mockMvc.perform(post("/api/v1/simulation/policy-impact")
                        .param("policyKey", "account-protection-default")
                        .param("candidatePolicyVersion", "2.0.0")
                        .param("maxSamples", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyKey").value("account-protection-default"))
                .andExpect(jsonPath("$.candidatePolicyVersion").value("2.0.0"))
                .andExpect(jsonPath("$.totalDecisions").value(10))
                .andExpect(jsonPath("$.divergentDecisionsCount").value(2))
                .andExpect(jsonPath("$.exceedsDivergenceThreshold").value(false));
    }

    @Test
    void analyzeImpactReturns422ForUnavailableCandidateVersion() throws Exception {
        when(policyImpactAnalysisService.analyzeImpact("account-protection-default", "9.9.9", 5000))
                .thenThrow(new ActivePolicyUnavailableException("account-protection-default"));

        mockMvc.perform(post("/api/v1/simulation/policy-impact")
                        .param("policyKey", "account-protection-default")
                        .param("candidatePolicyVersion", "9.9.9"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CANDIDATE_POLICY_VERSION_UNAVAILABLE"));
    }
}
