package io.github.viniciusssantos.accountshield.recovery.internal.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.RecoveryPage;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.RecoverySummary;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RecoverySearchControllerTest {

    private final RecoveryOperationsQuery query = mock(RecoveryOperationsQuery.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RecoverySearchController(query))
                .setControllerAdvice(new RecoverySearchProblemHandler())
                .build();
    }

    @Test
    void returnsMinimizedRecoveryPageWithNoStore() throws Exception {
        when(query.search(any())).thenReturn(new RecoveryPage(
                List.of(summary()), "next-safe-cursor", 25, true));

        mockMvc.perform(post("/api/v1/operator/recoveries/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELAYED\",\"minimumRiskScore\":40}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.recoveries[0].maskedSubjectReference").value("••••9f12"))
                .andExpect(jsonPath("$.recoveries[0].classificationRuleVersion").value("recovery-v3"))
                .andExpect(jsonPath("$.recoveries[0].accountReference").doesNotExist())
                .andExpect(jsonPath("$.recoveries[0].reviewer").doesNotExist())
                .andExpect(jsonPath("$.recoveries[0].identityChallengeId").doesNotExist())
                .andExpect(jsonPath("$.recoveries[0].authorizationId").doesNotExist());
    }

    @Test
    void rejectsInvalidRiskBoundsWithStableProblem() throws Exception {
        mockMvc.perform(post("/api/v1/operator/recoveries/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minimumRiskScore\":90,\"maximumRiskScore\":20}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RECOVERY_SEARCH"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void doesNotLeakDatabaseFailureDetails() throws Exception {
        when(query.search(any())).thenThrow(
                new DataRetrievalFailureException("account secret and internal SQL must not leak"));

        mockMvc.perform(post("/api/v1/operator/recoveries/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECOVERY_SEARCH_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("account secret"))));
    }

    private RecoverySummary summary() {
        return new RecoverySummary(
                "86e7e5fd-7137-4704-abee-4d9ea496970d",
                "••••9f12",
                "PASSWORD_RESET",
                "DELAYED",
                false,
                "DELAYED",
                "recovery-v3",
                68,
                Instant.parse("2026-07-29T22:00:00Z"),
                Instant.parse("2026-07-29T22:05:00Z"),
                Instant.parse("2026-07-30T22:00:00Z"),
                "50ca762f-10c1-4278-8aa2-6f63ad65db79",
                "NOT_APPLICABLE",
                true);
    }
}
