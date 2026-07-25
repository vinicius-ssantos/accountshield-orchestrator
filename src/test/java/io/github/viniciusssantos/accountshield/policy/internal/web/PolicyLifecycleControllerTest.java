package io.github.viniciusssantos.accountshield.policy.internal.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.viniciusssantos.accountshield.policy.CreatePolicyCommand;
import io.github.viniciusssantos.accountshield.policy.DuplicatePolicyVersionException;
import io.github.viniciusssantos.accountshield.policy.IllegalPolicyTransitionException;
import io.github.viniciusssantos.accountshield.policy.PendingPolicyVersionExistsException;
import io.github.viniciusssantos.accountshield.policy.PolicyAnalysisFailedException;
import io.github.viniciusssantos.accountshield.policy.PolicyAnalysisResult;
import io.github.viniciusssantos.accountshield.policy.PolicyAnalyzer;
import io.github.viniciusssantos.accountshield.policy.PolicyDefinition;
import io.github.viniciusssantos.accountshield.policy.PolicyLifecycleService;
import io.github.viniciusssantos.accountshield.policy.PolicyStatus;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionNotFoundException;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionSummary;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PolicyLifecycleControllerTest {

    private static final UUID STEP_UP_CHALLENGE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final PolicyLifecycleService lifecycleService = mock(PolicyLifecycleService.class);
    private final PolicyAnalyzer policyAnalyzer = new PolicyAnalyzer();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PolicyLifecycleController(lifecycleService, policyAnalyzer))
                .setControllerAdvice(new PolicyLifecycleProblemHandler())
                .build();
    }

    @Test
    void analyzeReturnsCleanResultForWellFormedDefinition() throws Exception {
        mockMvc.perform(post("/api/v1/policies/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "allowMaxScore": 29, "stepUpMaxScore": 69, "recoveryMaxScore": 89 }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnostics").isEmpty());
    }

    @Test
    void analyzeReturnsDiagnosticsForShadowedBandWithoutPersisting() throws Exception {
        mockMvc.perform(post("/api/v1/policies/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "allowMaxScore": 70, "stepUpMaxScore": 70, "recoveryMaxScore": 89 }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnostics[0].code").value("STEP_UP_BAND_SHADOWED"))
                .andExpect(jsonPath("$.diagnostics[0].severity").value("ERROR"));

        Mockito.verifyNoInteractions(lifecycleService);
    }

    @Test
    void analyzeReportsMissingThresholds() throws Exception {
        mockMvc.perform(post("/api/v1/policies/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnostics[0].code").value("ALLOW_MAX_SCORE_MISSING"))
                .andExpect(jsonPath("$.diagnostics[1].code").value("STEP_UP_MAX_SCORE_MISSING"))
                .andExpect(jsonPath("$.diagnostics[2].code").value("RECOVERY_MAX_SCORE_MISSING"));
    }

    @Test
    void createsDraftAndReturns201() throws Exception {
        PolicyVersionSummary summary = new PolicyVersionSummary(
                UUID.randomUUID(), "test-policy", "1.0.0", PolicyStatus.DRAFT,
                (short) 25, (short) 65, Instant.parse("2026-07-22T12:00:00Z"), null);
        when(lifecycleService.createDraft(any(CreatePolicyCommand.class)))
                .thenReturn(summary);

        mockMvc.perform(post("/api/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "policyKey": "test-policy", "version": "1.0.0", "allowMaxScore": 25, "stepUpMaxScore": 65 }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.policyKey").value("test-policy"));
    }

    @Test
    void activateReturns200WithActiveStatus() throws Exception {
        PolicyVersionSummary summary = new PolicyVersionSummary(
                UUID.randomUUID(), "test-policy", "1.0.0", PolicyStatus.ACTIVE,
                (short) 25, (short) 65, Instant.parse("2026-07-22T12:00:00Z"),
                Instant.parse("2026-07-22T12:05:00Z"));
        when(lifecycleService.activate(eq("test-policy"), eq("1.0.0"), eq(STEP_UP_CHALLENGE_ID), eq("admin-alice")))
                .thenReturn(summary);

        mockMvc.perform(activateRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void illegalTransitionReturns409() throws Exception {
        when(lifecycleService.activate(eq("test-policy"), eq("1.0.0"), eq(STEP_UP_CHALLENGE_ID), eq("admin-alice")))
                .thenThrow(new IllegalPolicyTransitionException(
                        "test-policy", "1.0.0", "DRAFT", "ACTIVE"));

        mockMvc.perform(activateRequest())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));
    }

    @Test
    void duplicateVersionReturns409() throws Exception {
        when(lifecycleService.createDraft(any(CreatePolicyCommand.class)))
                .thenThrow(new DuplicatePolicyVersionException("test-policy", "1.0.0"));

        mockMvc.perform(post("/api/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "policyKey": "test-policy", "version": "1.0.0", "allowMaxScore": 25, "stepUpMaxScore": 65 }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_VERSION_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.detail").value("Policy test-policy:1.0.0 already exists."));
    }

    @Test
    void pendingVersionExistsReturns409() throws Exception {
        when(lifecycleService.createDraft(any(CreatePolicyCommand.class)))
                .thenThrow(new PendingPolicyVersionExistsException("test-policy"));

        mockMvc.perform(post("/api/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "policyKey": "test-policy", "version": "1.0.0", "allowMaxScore": 25, "stepUpMaxScore": 65 }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PENDING_POLICY_VERSION_EXISTS"));
    }

    @Test
    void notFoundReturns404() throws Exception {
        when(lifecycleService.activate(eq("test-policy"), eq("9.9.9"), eq(STEP_UP_CHALLENGE_ID), eq("admin-alice")))
                .thenThrow(new PolicyVersionNotFoundException("test-policy", "9.9.9"));

        mockMvc.perform(post("/api/v1/policies/test-policy/9.9.9/activate")
                        .principal(new TestingAuthenticationToken("admin-alice", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stepUpBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POLICY_VERSION_NOT_FOUND"));
    }

    @Test
    void rejectsOutOfRangeScores() throws Exception {
        mockMvc.perform(post("/api/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "policyKey": "test-policy", "version": "1.0.0", "allowMaxScore": 100, "stepUpMaxScore": 200 }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void activateWithoutStepUpChallengeIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/policies/test-policy/1.0.0/activate")
                        .principal(new TestingAuthenticationToken("admin-alice", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validateReturns422WhenAnalysisFails() throws Exception {
        PolicyAnalysisResult result = policyAnalyzer.analyze(
                new PolicyDefinition((short) 70, (short) 70, (short) 89));
        when(lifecycleService.validate("test-policy", "1.0.0"))
                .thenThrow(new PolicyAnalysisFailedException("test-policy", "1.0.0", result));

        mockMvc.perform(post("/api/v1/policies/test-policy/1.0.0/validate")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("POLICY_ANALYSIS_FAILED"))
                .andExpect(jsonPath("$.diagnostics[0].code").value("STEP_UP_BAND_SHADOWED"));
    }

    @Test
    void retireReturns200WithRetiredStatus() throws Exception {
        PolicyVersionSummary summary = new PolicyVersionSummary(
                UUID.randomUUID(), "test-policy", "1.0.0", PolicyStatus.RETIRED,
                (short) 25, (short) 65, Instant.parse("2026-07-22T12:00:00Z"),
                Instant.parse("2026-07-22T12:05:00Z"));
        when(lifecycleService.retire(eq("test-policy"), eq("1.0.0"), eq(STEP_UP_CHALLENGE_ID), eq("admin-alice")))
                .thenReturn(summary);

        mockMvc.perform(post("/api/v1/policies/test-policy/1.0.0/retire")
                        .principal(new TestingAuthenticationToken("admin-alice", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stepUpBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"));
    }

    private MockHttpServletRequestBuilder activateRequest() {
        return post("/api/v1/policies/test-policy/1.0.0/activate")
                .principal(new TestingAuthenticationToken("admin-alice", null))
                .contentType(MediaType.APPLICATION_JSON)
                .content(stepUpBody());
    }

    private String stepUpBody() {
        return "{ \"stepUpChallengeId\": \"" + STEP_UP_CHALLENGE_ID + "\" }";
    }
}
