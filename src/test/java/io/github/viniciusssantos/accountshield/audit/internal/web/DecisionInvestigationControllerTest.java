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
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionInvestigationDetail;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionInvestigationPage;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionInvestigationSummary;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionReasonSummary;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionTimelineEntry;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.ExecutionProvenanceSummary;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.InvestigationSections;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.OutboxSummary;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.PolicyProvenanceSummary;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.SectionAvailability;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.SignalProvenanceSummary;
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

class DecisionInvestigationControllerTest {

    private static final String DECISION_REFERENCE = "86e7e5fd-7137-4704-abee-4d9ea496970d";

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
        DecisionInvestigationSummary summary = summary();
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
    void returnsMinimizedDeterministicTimelineWithNoStore() throws Exception {
        Instant requestedAt = Instant.parse("2026-07-27T23:29:59Z");
        Instant decidedAt = Instant.parse("2026-07-27T23:30:00Z");
        DecisionInvestigationDetail detail = new DecisionInvestigationDetail(
                summary(),
                "••••-123",
                List.of(new DecisionReasonSummary("KNOWN_DEVICE", -8, 0)),
                new SignalProvenanceSummary(
                        "CLIENT_SUPPLIED",
                        requestedAt,
                        "HIGH",
                        "1",
                        "SIMULATED",
                        true,
                        false),
                new PolicyProvenanceSummary(
                        "account-protection-default",
                        "1.1.0",
                        "ACTIVE_POLICY",
                        null,
                        null,
                        null),
                new ExecutionProvenanceSummary(
                        "risk-v1",
                        null,
                        "risk-reasons-v1",
                        "decision-engine-v1",
                        null,
                        false,
                        true),
                List.of(),
                null,
                List.of(new OutboxSummary(
                        "outbox-safe-reference",
                        "PROTECTION_DECISION_MADE",
                        "PUBLISHED",
                        decidedAt,
                        decidedAt.plusSeconds(1),
                        null,
                        0)),
                List.of(
                        new DecisionTimelineEntry(
                                "request-safe-reference",
                                "REQUEST_RECEIVED",
                                "RECEIVED",
                                requestedAt),
                        new DecisionTimelineEntry(
                                DECISION_REFERENCE,
                                "DECISION_RECORDED",
                                "ALLOW",
                                decidedAt),
                        new DecisionTimelineEntry(
                                "outbox-safe-reference",
                                "OUTBOX_EVENT_RECORDED",
                                "RECORDED",
                                decidedAt)),
                new InvestigationSections(
                        SectionAvailability.NOT_APPLICABLE,
                        SectionAvailability.NOT_APPLICABLE,
                        SectionAvailability.AVAILABLE),
                false);
        when(query.investigate(DECISION_REFERENCE)).thenReturn(Optional.of(detail));

        mockMvc.perform(post("/api/v1/operator/decisions/investigate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "decisionReference": "%s" }
                                """.formatted(DECISION_REFERENCE)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.decision.decisionReference").value(DECISION_REFERENCE))
                .andExpect(jsonPath("$.maskedSubjectReference").value("••••-123"))
                .andExpect(jsonPath("$.reasons[0].code").value("KNOWN_DEVICE"))
                .andExpect(jsonPath("$.signalProvenance.state").value("SIMULATED"))
                .andExpect(jsonPath("$.policyProvenance.policyVersion").value("1.1.0"))
                .andExpect(jsonPath("$.sections.outbox").value("AVAILABLE"))
                .andExpect(jsonPath("$.timeline[0].kind").value("REQUEST_RECEIVED"))
                .andExpect(jsonPath("$.timeline[1].kind").value("DECISION_RECORDED"))
                .andExpect(jsonPath("$.accountReference").doesNotExist())
                .andExpect(jsonPath("$.normalizedContext").doesNotExist())
                .andExpect(jsonPath("$.requestFingerprint").doesNotExist())
                .andExpect(jsonPath("$.providerPayload").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("raw-account-reference"))));
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
    void rejectsMalformedDecisionReferenceWithoutEchoingIt() throws Exception {
        String malformedReference = "unsafe decision reference";
        mockMvc.perform(post("/api/v1/operator/decisions/investigate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "decisionReference": "%s" }
                                """.formatted(malformedReference)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DECISION_SEARCH"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(malformedReference))));
    }

    @Test
    void returnsGenericNotFoundProblem() throws Exception {
        when(query.investigate(DECISION_REFERENCE)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/operator/decisions/investigate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "decisionReference": "%s" }
                                """.formatted(DECISION_REFERENCE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DECISION_INVESTIGATION_NOT_FOUND"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(DECISION_REFERENCE))));
    }

    @Test
    void doesNotLeakDatabaseFailureDetails() throws Exception {
        when(query.investigate(DECISION_REFERENCE)).thenThrow(
                new DataRetrievalFailureException("account secret and internal SQL must not leak"));

        mockMvc.perform(post("/api/v1/operator/decisions/investigate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "decisionReference": "%s" }
                                """.formatted(DECISION_REFERENCE)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DECISION_SEARCH_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("account secret"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("internal SQL"))));
    }

    private DecisionInvestigationSummary summary() {
        return new DecisionInvestigationSummary(
                DECISION_REFERENCE,
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
    }
}
