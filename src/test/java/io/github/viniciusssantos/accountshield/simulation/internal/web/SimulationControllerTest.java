package io.github.viniciusssantos.accountshield.simulation.internal.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.viniciusssantos.accountshield.simulation.ReplayResult;
import io.github.viniciusssantos.accountshield.simulation.ShadowEvaluationResult;
import io.github.viniciusssantos.accountshield.simulation.SimulationService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SimulationControllerTest {

    private final SimulationService simulationService = mock(SimulationService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SimulationController(simulationService))
                .build();
    }

    @Test
    void replayReturnsResultWhenFound() throws Exception {
        UUID requestId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        when(simulationService.replay(requestId))
                .thenReturn(Optional.of(new ReplayResult(
                        requestId, "ALLOW", "ALLOW", 10, 10,
                        "account-protection-default", "1.0.0", true)));

        mockMvc.perform(get("/api/v1/simulation/replay/550e8400-e29b-41d4-a716-446655440000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches").value(true))
                .andExpect(jsonPath("$.originalOutcome").value("ALLOW"))
                .andExpect(jsonPath("$.replayedOutcome").value("ALLOW"));
    }

    @Test
    void replayReturns404WhenNotFound() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(simulationService.replay(requestId))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/simulation/replay/" + requestId))
                .andExpect(status().isNotFound());
    }

    @Test
    void shadowReturnsResultWithDivergence() throws Exception {
        when(simulationService.evaluateShadow(eq("account-protection-default"), eq(50), eq("2.0.0")))
                .thenReturn(new ShadowEvaluationResult(
                        "ALLOW", "TEMPORARILY_BLOCK",
                        "1.0.0", "2.0.0", 50, true));

        mockMvc.perform(post("/api/v1/simulation/shadow")
                        .param("policyKey", "account-protection-default")
                        .param("riskScore", "50")
                        .param("candidatePolicyVersion", "2.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diverged").value(true))
                .andExpect(jsonPath("$.liveOutcome").value("ALLOW"))
                .andExpect(jsonPath("$.shadowOutcome").value("TEMPORARILY_BLOCK"));
    }

    @Test
    void shadowReturnsConvergedWhenOutcomesMatch() throws Exception {
        when(simulationService.evaluateShadow(eq("account-protection-default"), eq(20), eq("2.0.0")))
                .thenReturn(new ShadowEvaluationResult(
                        "ALLOW", "ALLOW",
                        "1.0.0", "2.0.0", 20, false));

        mockMvc.perform(post("/api/v1/simulation/shadow")
                        .param("policyKey", "account-protection-default")
                        .param("riskScore", "20")
                        .param("candidatePolicyVersion", "2.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diverged").value(false));
    }
}
