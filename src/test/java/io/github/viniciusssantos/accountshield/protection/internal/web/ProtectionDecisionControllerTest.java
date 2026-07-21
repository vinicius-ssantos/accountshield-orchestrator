package io.github.viniciusssantos.accountshield.protection.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.viniciusssantos.accountshield.policy.ActivePolicyUnavailableException;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskBand;
import io.github.viniciusssantos.accountshield.risk.RiskReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProtectionDecisionControllerTest {

    private final ProtectionDecisionService service = mock(ProtectionDecisionService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProtectionDecisionController(service))
                .setControllerAdvice(new ProtectionDecisionProblemHandler())
                .build();
    }

    @Test
    void returnsCreatedExplainableDecisionAndDefaultsNetworkRiskToLow() throws Exception {
        when(service.decide(any())).thenReturn(new ProtectionDecisionResult(
                UUID.fromString("73f09515-64da-4130-91ac-f1159efaeeb1"),
                UUID.fromString("95ba12b2-36ef-4cbb-861f-76974557e038"),
                ProtectionOutcome.REQUIRE_STEP_UP,
                30,
                RiskBand.MEDIUM,
                "risk-rules-1.0",
                "account-protection-default",
                "1.0.0",
                List.of(new RiskReason("FAILED_ATTEMPTS", 30)),
                Instant.parse("2026-07-20T03:00:00Z")));

        mockMvc.perform(post("/api/v1/protection-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountReference": "account-opaque-123",
                                  "eventType": "LOGIN_ATTEMPT",
                                  "failedAttempts": 10,
                                  "newDevice": false,
                                  "impossibleTravel": false,
                                  "compromisedCredential": false
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("REQUIRE_STEP_UP"))
                .andExpect(jsonPath("$.riskScore").value(30))
                .andExpect(jsonPath("$.algorithmVersion").value("risk-rules-1.0"))
                .andExpect(jsonPath("$.reasons[0].code").value("FAILED_ATTEMPTS"));

        ArgumentCaptor<ProtectionDecisionCommand> commandCaptor =
                ArgumentCaptor.forClass(ProtectionDecisionCommand.class);
        verify(service).decide(commandCaptor.capture());
        assertThat(commandCaptor.getValue().signals().networkRiskLevel()).isEqualTo(NetworkRiskLevel.LOW);
    }

    @Test
    void returnsStableProblemForOutOfRangeSignals() throws Exception {
        mockMvc.perform(post("/api/v1/protection-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountReference": "account-opaque-123",
                                  "eventType": "LOGIN_ATTEMPT",
                                  "failedAttempts": 21
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROTECTION_REQUEST"));
    }

    @Test
    void returnsNonLeakingProblemWhenActivePolicyIsUnavailable() throws Exception {
        when(service.decide(any())).thenThrow(new ActivePolicyUnavailableException("account-protection-default"));

        mockMvc.perform(post("/api/v1/protection-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountReference": "account-opaque-123",
                                  "eventType": "LOGIN_ATTEMPT",
                                  "failedAttempts": 0
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACTIVE_POLICY_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value("A protection decision cannot be produced safely at this time."));
    }
}
