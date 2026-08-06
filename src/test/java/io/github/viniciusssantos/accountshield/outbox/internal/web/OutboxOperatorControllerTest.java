package io.github.viniciusssantos.accountshield.outbox.internal.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.viniciusssantos.accountshield.outbox.OutboxOperatorQuery;
import io.github.viniciusssantos.accountshield.outbox.OutboxOperatorQuery.OutboxHealthSummary;
import io.github.viniciusssantos.accountshield.outbox.OutboxOperatorQuery.OutboxOperatorEventPage;
import io.github.viniciusssantos.accountshield.outbox.OutboxOperatorQuery.OutboxOperatorEventRecord;
import io.github.viniciusssantos.accountshield.outbox.OutboxOperatorQuery.OutboxOperatorSearchResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OutboxOperatorControllerTest {

    private final OutboxOperatorQuery query = mock(OutboxOperatorQuery.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new OutboxOperatorController(query))
                .setControllerAdvice(new OutboxOperatorProblemHandler())
                .build();
    }

    @Test
    void returnsHealthAndEventsWithNoStore() throws Exception {
        when(query.search(any())).thenReturn(new OutboxOperatorSearchResult(
                new OutboxHealthSummary(1, 2, 0, 3, 45.0, 1, 4, 15, Instant.parse("2026-07-30T10:00:00Z")),
                new OutboxOperatorEventPage(List.of(deadLetteredRecord()), "next-safe-cursor", 25, true)));

        mockMvc.perform(post("/api/v1/operator/outbox/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.health.pendingCount").value(1))
                .andExpect(jsonPath("$.health.retryingCount").value(2))
                .andExpect(jsonPath("$.events.records[0].maskedCorrelationReference").value("••••1234"))
                .andExpect(jsonPath("$.events.records[0].deadLetterFailureCategory").value("ConnectException"))
                .andExpect(jsonPath("$.events.records[0].lastError").doesNotExist())
                .andExpect(jsonPath("$.events.records[0].payload").doesNotExist())
                .andExpect(jsonPath("$.events.records[0].claimedBy").doesNotExist());
    }

    @Test
    void rejectsOutOfBoundsAttemptCountWithStableProblem() throws Exception {
        mockMvc.perform(post("/api/v1/operator/outbox/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minAttemptCount\": -1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OUTBOX_SEARCH"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void doesNotLeakDatabaseFailureDetails() throws Exception {
        when(query.search(any())).thenThrow(
                new DataRetrievalFailureException("internal connection string must not leak"));

        mockMvc.perform(post("/api/v1/operator/outbox/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("OUTBOX_SEARCH_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("internal connection string"))));
    }

    private OutboxOperatorEventRecord deadLetteredRecord() {
        return new OutboxOperatorEventRecord(
                UUID.randomUUID(),
                "Recovery",
                "RECOVERY_MANUAL_REVIEW_REQUIRED",
                "DEAD_LETTERED",
                5,
                Instant.parse("2026-07-30T09:00:00Z"),
                null,
                Instant.parse("2026-07-30T09:05:00Z"),
                null,
                false,
                null,
                "integration-event-1.0",
                "••••1234",
                true,
                "ConnectException");
    }
}
