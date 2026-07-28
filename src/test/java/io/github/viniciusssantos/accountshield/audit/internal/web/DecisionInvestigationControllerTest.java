package io.github.viniciusssantos.accountshield.audit.internal.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionInvestigationPage;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionInvestigationSummary;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DecisionInvestigationControllerTest {

    private final DecisionInvestigationQuery query = mock(DecisionInvestigationQuery.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DecisionInvestigationController(query))
                .setControllerAdvice(new DecisionInvestigationProblemHandler())
                .build();
    }

    @Test
    void returnsMinimizedPageWithNoStore() throws Exception {
        DecisionInvestigationSummary summary = new DecisionInvestigationSummary(
                "86e7e5fd-7137-4704-abee-4d9ea496970d",
                "corr-safe-123",
                "LOGIN_ATTEMPT",
                "ALLOW",
                12,
                "LOW",
                "account-protection-default",
                "1.1.0",
                Instant.parse("2026-07-27T23:30:00Z"),
                false,
                true,
                true);
        when(query.search(any())).thenReturn(new DecisionInvestigationPage(
                List.of(summary), "next-safe-cursor", 25, true));

        mockMvc.perform(post("/api/v1/operator/decisions/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "correlationId": "corr-safe-123" }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.pageSize").value(25))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.decisions[0].correlationId").value("corr-safe-123"))
                .andExpect(jsonPath("$.decisions[0].riskBand").value("LOW"))
                .andExpect(jsonPath("$.decisions[0].simulated").value(true))
                .andExpect(jsonPath("$.decisions[0].accountReference").doesNotExist())
                .andExpect(jsonPath("$.decisions[0].normalizedContext").doesNotExist())
                .andExpect(jsonPath("$.decisions[0].requestFingerprint").doesNotExist());
    }

    @Test
    void rejectsMalformedCorrelationWithStableProblem() throws Exception {
        mockMvc.perform(post("/api/v1/operator/decisions/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "correlationId": "unsafe correlation with spaces" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_DECISION_SEARCH"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void doesNotLeakDatabaseFailureDetails() throws Exception {
        when(query.search(any())).thenThrow(
                new DataRetrievalFailureException("account secret and internal SQL must not leak"));

        mockMvc.perform(post("/api/v1/operator/decisions/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DECISION_SEARCH_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("account secret"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("internal SQL"))));
    }
}
