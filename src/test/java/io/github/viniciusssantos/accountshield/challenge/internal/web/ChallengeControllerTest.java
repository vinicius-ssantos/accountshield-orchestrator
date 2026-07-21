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
import io.github.viniciusssantos.accountshield.challenge.InvalidChallengeStateException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChallengeControllerTest {

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
    void returnsOkOnSuccessfulVerification() throws Exception {
        UUID challengeId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        when(challengeService.verify(any())).thenReturn(new ChallengeResult(
                challengeId, ChallengeStatus.VERIFIED, true, 2, Instant.parse("2026-07-20T03:10:00Z")));

        mockMvc.perform(post("/api/v1/challenges/550e8400-e29b-41d4-a716-446655440000/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "providedCode": "123456" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challengeId").value("550e8400-e29b-41d4-a716-446655440000"))
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.remainingAttempts").value(2));
    }

    @Test
    void returnsConflictOnFailedChallenge() throws Exception {
        UUID challengeId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        when(challengeService.verify(any())).thenThrow(new InvalidChallengeStateException(
                challengeId, ChallengeStatus.FAILED));

        mockMvc.perform(post("/api/v1/challenges/550e8400-e29b-41d4-a716-446655440000/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "providedCode": "wrong" }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CHALLENGE_STATE"));
    }

    @Test
    void returnsGoneOnExpiredChallenge() throws Exception {
        UUID challengeId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        when(challengeService.verify(any())).thenThrow(new InvalidChallengeStateException(
                challengeId, ChallengeStatus.EXPIRED));

        mockMvc.perform(post("/api/v1/challenges/550e8400-e29b-41d4-a716-446655440000/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "providedCode": "123456" }
                                """))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INVALID_CHALLENGE_STATE"));
    }

    @Test
    void rejectsBlankCode() throws Exception {
        mockMvc.perform(post("/api/v1/challenges/550e8400-e29b-41d4-a716-446655440000/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "providedCode": "   " }
                                """))
                .andExpect(status().isBadRequest());
    }
}