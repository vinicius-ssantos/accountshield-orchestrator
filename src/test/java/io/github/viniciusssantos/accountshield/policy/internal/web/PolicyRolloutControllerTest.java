package io.github.viniciusssantos.accountshield.policy.internal.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.viniciusssantos.accountshield.policy.PolicyRollout;
import io.github.viniciusssantos.accountshield.policy.PolicyRolloutNotFoundException;
import io.github.viniciusssantos.accountshield.policy.PolicyRolloutService;
import io.github.viniciusssantos.accountshield.policy.PolicyRolloutStatus;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionNotFoundException;
import io.github.viniciusssantos.accountshield.policy.RolloutAlreadyActiveException;
import io.github.viniciusssantos.accountshield.policy.RolloutCandidateNotApprovedException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PolicyRolloutControllerTest {

    private static final UUID STEP_UP_CHALLENGE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String POLICY_KEY = "account-protection-default";

    private final PolicyRolloutService rolloutService = mock(PolicyRolloutService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PolicyRolloutController(rolloutService))
                .setControllerAdvice(new PolicyLifecycleProblemHandler())
                .build();
    }

    @Test
    void startRolloutReturnsTheCreatedRollout() throws Exception {
        PolicyRollout rollout = rollout(10, PolicyRolloutStatus.ACTIVE);
        when(rolloutService.startRollout(
                        eq(POLICY_KEY), eq("2.0.0"), eq(10), eq(STEP_UP_CHALLENGE_ID), eq("operator")))
                .thenReturn(rollout);

        mockMvc.perform(post("/api/v1/policies/" + POLICY_KEY + "/rollout")
                        .principal(new TestingAuthenticationToken("operator", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "candidateVersion": "2.0.0", "rolloutPercentage": 10,
                                  "stepUpChallengeId": "22222222-2222-2222-2222-222222222222" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateVersion").value("2.0.0"))
                .andExpect(jsonPath("$.rolloutPercentage").value(10))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void startRolloutReturns409WhenCandidateNotApproved() throws Exception {
        when(rolloutService.startRollout(
                        eq(POLICY_KEY), eq("2.0.0"), eq(10), eq(STEP_UP_CHALLENGE_ID), eq("operator")))
                .thenThrow(new RolloutCandidateNotApprovedException(POLICY_KEY, "2.0.0"));

        mockMvc.perform(post("/api/v1/policies/" + POLICY_KEY + "/rollout")
                        .principal(new TestingAuthenticationToken("operator", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "candidateVersion": "2.0.0", "rolloutPercentage": 10,
                                  "stepUpChallengeId": "22222222-2222-2222-2222-222222222222" }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLLOUT_CANDIDATE_NOT_APPROVED"));
    }

    @Test
    void startRolloutReturns409WhenAlreadyActive() throws Exception {
        when(rolloutService.startRollout(
                        eq(POLICY_KEY), eq("2.0.0"), eq(10), eq(STEP_UP_CHALLENGE_ID), eq("operator")))
                .thenThrow(new RolloutAlreadyActiveException(POLICY_KEY));

        mockMvc.perform(post("/api/v1/policies/" + POLICY_KEY + "/rollout")
                        .principal(new TestingAuthenticationToken("operator", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "candidateVersion": "2.0.0", "rolloutPercentage": 10,
                                  "stepUpChallengeId": "22222222-2222-2222-2222-222222222222" }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLLOUT_ALREADY_ACTIVE"));
    }

    @Test
    void startRolloutReturns404WhenCandidateVersionNotFound() throws Exception {
        when(rolloutService.startRollout(
                        eq(POLICY_KEY), eq("9.9.9"), eq(10), eq(STEP_UP_CHALLENGE_ID), eq("operator")))
                .thenThrow(new PolicyVersionNotFoundException(POLICY_KEY, "9.9.9"));

        mockMvc.perform(post("/api/v1/policies/" + POLICY_KEY + "/rollout")
                        .principal(new TestingAuthenticationToken("operator", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "candidateVersion": "9.9.9", "rolloutPercentage": 10,
                                  "stepUpChallengeId": "22222222-2222-2222-2222-222222222222" }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POLICY_VERSION_NOT_FOUND"));
    }

    @Test
    void updatePercentageReturnsTheUpdatedRollout() throws Exception {
        PolicyRollout rollout = rollout(40, PolicyRolloutStatus.ACTIVE);
        when(rolloutService.updatePercentage(eq(POLICY_KEY), eq(40), eq(STEP_UP_CHALLENGE_ID), eq("operator")))
                .thenReturn(rollout);

        mockMvc.perform(patch("/api/v1/policies/" + POLICY_KEY + "/rollout")
                        .principal(new TestingAuthenticationToken("operator", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "rolloutPercentage": 40,
                                  "stepUpChallengeId": "22222222-2222-2222-2222-222222222222" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolloutPercentage").value(40));
    }

    @Test
    void rollbackDoesNotRequireAStepUpChallenge() throws Exception {
        PolicyRollout rollout = rollout(10, PolicyRolloutStatus.ROLLED_BACK);
        when(rolloutService.rollback(POLICY_KEY, "operator")).thenReturn(rollout);

        mockMvc.perform(post("/api/v1/policies/" + POLICY_KEY + "/rollout/rollback")
                        .principal(new TestingAuthenticationToken("operator", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));
    }

    @Test
    void statusReturnsTheActiveRollout() throws Exception {
        PolicyRollout rollout = rollout(10, PolicyRolloutStatus.ACTIVE);
        when(rolloutService.findActiveRollout(POLICY_KEY)).thenReturn(java.util.Optional.of(rollout));

        mockMvc.perform(get("/api/v1/policies/" + POLICY_KEY + "/rollout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyKey").value(POLICY_KEY))
                .andExpect(jsonPath("$.candidateVersion").value("2.0.0"));
    }

    @Test
    void statusReturns404WhenNoActiveRolloutExists() throws Exception {
        when(rolloutService.findActiveRollout(POLICY_KEY)).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/v1/policies/" + POLICY_KEY + "/rollout"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POLICY_ROLLOUT_NOT_FOUND"));
    }

    private PolicyRollout rollout(int percentage, PolicyRolloutStatus status) {
        Instant now = Instant.parse("2026-07-26T00:00:00Z");
        return new PolicyRollout(
                UUID.randomUUID(), POLICY_KEY, "2.0.0", percentage, status, now, "operator", now,
                status == PolicyRolloutStatus.ROLLED_BACK ? now : null,
                status == PolicyRolloutStatus.ROLLED_BACK ? "operator" : null);
    }
}
