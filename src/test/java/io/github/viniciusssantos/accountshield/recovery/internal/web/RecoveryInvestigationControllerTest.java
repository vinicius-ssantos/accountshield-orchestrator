package io.github.viniciusssantos.accountshield.recovery.internal.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.ChallengeEvidence;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.RecoveryDetail;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.RecoverySummary;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.SectionAvailability;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RecoveryInvestigationControllerTest {

    private static final String RECOVERY_REFERENCE =
            "86e7e5fd-7137-4704-abee-4d9ea496970d";

    private final RecoveryOperationsQuery query = mock(RecoveryOperationsQuery.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RecoveryInvestigationController(query))
                .setControllerAdvice(new RecoveryInvestigationProblemHandler())
                .build();
    }

    @Test
    void returnsMinimizedDetailWithNoStore() throws Exception {
        when(query.investigate(RECOVERY_REFERENCE)).thenReturn(Optional.of(detail()));

        mockMvc.perform(post("/api/v1/operator/recoveries/investigate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recoveryReference\":\"" + RECOVERY_REFERENCE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.recovery.maskedSubjectReference").value("••••9f12"))
                .andExpect(jsonPath("$.challenges[0].challengeType").value("TOTP_SIMULATED"))
                .andExpect(jsonPath("$.challengeAvailability").value("AVAILABLE"))
                .andExpect(jsonPath("$.recovery.accountReference").doesNotExist())
                .andExpect(jsonPath("$.reviewer").doesNotExist())
                .andExpect(jsonPath("$.challenges[0].code").doesNotExist())
                .andExpect(jsonPath("$.challenges[0].providerPayload").doesNotExist())
                .andExpect(jsonPath("$.authorizationId").doesNotExist());
    }

    @Test
    void returnsGenericNotFoundWithoutEchoingReference() throws Exception {
        when(query.investigate(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/operator/recoveries/investigate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recoveryReference\":\"" + RECOVERY_REFERENCE + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECOVERY_INVESTIGATION_NOT_FOUND"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(RECOVERY_REFERENCE))));
    }

    @Test
    void rejectsInvalidReferenceWithStableProblem() throws Exception {
        mockMvc.perform(post("/api/v1/operator/recoveries/investigate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recoveryReference\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RECOVERY_INVESTIGATION"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void doesNotLeakDatabaseFailureDetails() throws Exception {
        when(query.investigate(anyString())).thenThrow(
                new DataRetrievalFailureException("secret reviewer and internal SQL must not leak"));

        mockMvc.perform(post("/api/v1/operator/recoveries/investigate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recoveryReference\":\"" + RECOVERY_REFERENCE + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECOVERY_INVESTIGATION_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret reviewer"))));
    }

    private RecoveryDetail detail() {
        return new RecoveryDetail(
                summary(),
                "605147c4-fc37-4556-9669-c688764e20e9",
                false,
                List.of(new ChallengeEvidence(
                        "29f61c9f-b7b8-4a68-9224-49bd012c16fd",
                        "TOTP_SIMULATED",
                        "RECOVERY_IDENTITY",
                        "ISSUED",
                        Instant.parse("2026-07-29T22:01:00Z"),
                        Instant.parse("2026-07-29T22:11:00Z"),
                        null)),
                SectionAvailability.AVAILABLE,
                false);
    }

    private RecoverySummary summary() {
        return new RecoverySummary(
                RECOVERY_REFERENCE,
                "••••9f12",
                "PASSWORD_RESET",
                "VERIFYING_IDENTITY",
                false,
                "IMMEDIATE",
                "recovery-v3",
                68,
                Instant.parse("2026-07-29T22:00:00Z"),
                Instant.parse("2026-07-29T22:01:00Z"),
                null,
                "50ca762f-10c1-4278-8aa2-6f63ad65db79",
                "NOT_APPLICABLE",
                true);
    }
}
