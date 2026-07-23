package io.github.viniciusssantos.accountshield.challenge.internal.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.viniciusssantos.accountshield.challenge.ChallengeResult;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeUseRejectedException;
import io.github.viniciusssantos.accountshield.challenge.InvalidChallengeStateException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChallengeControllerTest {

    private static final String CHALLENGE_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String CONTEXT_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";

    private final ChallengeService challengeService = mock(ChallengeService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ChallengeController(challengeService))
                .setControllerAdvice(new ChallengeProblemHandler())
                .build();
    }

    @Test
    void returnsOkOnSuccessfulBoundVerification() throws Exception {
        UUID challengeId = UUID.fromString(CHALLENGE_ID);
        when(challengeService.verify(any())).thenReturn(new ChallengeResult(
                challengeId,
                ChallengeStatus.VERIFIED,
                true,
                2,
                Instant.parse("2026-07-20T03:10:00Z")));

        mockMvc.perform(post("/api/v1/challenges/{challengeId}/verify", CHALLENGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verificationBody("123456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challengeId").value(CHALLENGE_ID))
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.remainingAttempts").value(2));
    }

    @Test
    void returnsGenericConflictWhenPurposeOrContextIsRejected() throws Exception {
        when(challengeService.verify(any())).thenThrow(new ChallengeUseRejectedException());

        mockMvc.perform(post("/api/v1/challenges/{challengeId}/verify", CHALLENGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verificationBody("123456")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHALLENGE_USE_REJECTED"))
                .andExpect(jsonPath("$.detail")
                        .value("The challenge cannot be used for the requested operation."));
    }

    @Test
    void returnsConflictOnFailedChallenge() throws Exception {
        UUID challengeId = UUID.fromString(CHALLENGE_ID);
        when(challengeService.verify(any())).thenThrow(new InvalidChallengeStateException(
                challengeId, ChallengeStatus.FAILED));

        mockMvc.perform(post("/api/v1/challenges/{challengeId}/verify", CHALLENGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verificationBody("wrong")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CHALLENGE_STATE"));
    }

    @Test
    void returnsGoneOnExpiredChallenge() throws Exception {
        UUID challengeId = UUID.fromString(CHALLENGE_ID);
        when(challengeService.verify(any())).thenThrow(new InvalidChallengeStateException(
                challengeId, ChallengeStatus.EXPIRED));

        mockMvc.perform(post("/api/v1/challenges/{challengeId}/verify", CHALLENGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verificationBody("123456")))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INVALID_CHALLENGE_STATE"));
    }

    @Test
    void rejectsBlankCode() throws Exception {
        mockMvc.perform(post("/api/v1/challenges/{challengeId}/verify", CHALLENGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verificationBody("   ")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingPurposeOrContext() throws Exception {
        mockMvc.perform(post("/api/v1/challenges/{challengeId}/verify", CHALLENGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "providedCode": "123456" }
                                """))
                .andExpect(status().isBadRequest());
    }

    private String verificationBody(String code) {
        return """
                {
                  "providedCode": "%s",
                  "purpose": "PROTECTION_STEP_UP",
                  "contextId": "%s"
                }
                """.formatted(code, CONTEXT_ID);
    }
}
