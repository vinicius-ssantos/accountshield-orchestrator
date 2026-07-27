package io.github.viniciusssantos.accountshield.evidence.internal.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.viniciusssantos.accountshield.audit.DecisionReasonContribution;
import io.github.viniciusssantos.accountshield.evidence.EvidenceBundle;
import io.github.viniciusssantos.accountshield.evidence.EvidenceBundleContent;
import io.github.viniciusssantos.accountshield.evidence.EvidenceBundleService;
import io.github.viniciusssantos.accountshield.evidence.EvidenceExportCommand;
import io.github.viniciusssantos.accountshield.evidence.EvidenceManifest;
import io.github.viniciusssantos.accountshield.evidence.EvidenceVerificationResult;
import io.github.viniciusssantos.accountshield.simulation.ReplayResult;
import io.github.viniciusssantos.accountshield.risk.RiskBand;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class EvidenceExportControllerTest {

    private final EvidenceBundleService evidenceBundleService = mock(EvidenceBundleService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new EvidenceExportController(evidenceBundleService))
                .setControllerAdvice(new EvidenceProblemHandler())
                .build();
    }

    @Test
    void exportReturnsTheBundleUsingTheAuthenticatedActor() throws Exception {
        UUID protectionRequestId = UUID.randomUUID();
        EvidenceBundle bundle = sampleBundle(protectionRequestId);
        when(evidenceBundleService.exportBundle(any())).thenReturn(Optional.of(bundle));

        mockMvc.perform(post("/api/v1/evidence/export")
                        .principal(new TestingAuthenticationToken("operator-1", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"protectionRequestId\":\"" + protectionRequestId + "\",\"reason\":\"incident review\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest.exportedBy").value("operator-1"));

        verify(evidenceBundleService).exportBundle(
                eq(new EvidenceExportCommand(protectionRequestId, "operator-1", "incident review")));
    }

    @Test
    void exportReturns404WhenNoDecisionExistsForTheProtectionRequest() throws Exception {
        UUID protectionRequestId = UUID.randomUUID();
        when(evidenceBundleService.exportBundle(any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/evidence/export")
                        .principal(new TestingAuthenticationToken("operator-1", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"protectionRequestId\":\"" + protectionRequestId + "\",\"reason\":\"incident review\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void exportReturns400WhenReasonIsBlank() throws Exception {
        UUID protectionRequestId = UUID.randomUUID();
        when(evidenceBundleService.exportBundle(any()))
                .thenThrow(new IllegalArgumentException("reason must contain between 1 and 500 characters"));

        mockMvc.perform(post("/api/v1/evidence/export")
                        .principal(new TestingAuthenticationToken("operator-1", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"protectionRequestId\":\"" + protectionRequestId + "\",\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EVIDENCE_INVALID_REQUEST"));
    }

    @Test
    void verifyDelegatesToTheService() throws Exception {
        EvidenceBundle bundle = sampleBundle(UUID.randomUUID());
        when(evidenceBundleService.verify(bundle)).thenReturn(EvidenceVerificationResult.ok());

        mockMvc.perform(post("/api/v1/evidence/verify")
                        .principal(new TestingAuthenticationToken("operator-1", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bundle)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    private EvidenceBundle sampleBundle(UUID protectionRequestId) {
        UUID decisionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        EvidenceBundleContent content = new EvidenceBundleContent(
                EvidenceBundleContent.BUNDLE_SCHEMA_VERSION,
                decisionId,
                protectionRequestId,
                "pseudonym-abc",
                "fingerprint-abc",
                "risk-rules-1.0",
                "account-protection-default",
                "1.0.0",
                "ALLOW",
                10,
                new TreeMap<>(),
                now,
                List.of(new DecisionReasonContribution("LOW_RISK", 0, java.util.Map.of())),
                new ReplayResult(
                        protectionRequestId, true, "ALLOW", "ALLOW", 10, 10,
                        RiskBand.LOW, RiskBand.LOW, List.of(), List.of(),
                        "account-protection-default", "1.0.0", "risk-rules-1.0",
                        "risk-signal-envelope-1.0", "risk-reason-catalog-1.0", "decision-engine-1.0", List.of()),
                null);
        EvidenceManifest manifest = new EvidenceManifest(
                EvidenceBundleContent.BUNDLE_SCHEMA_VERSION,
                decisionId,
                protectionRequestId,
                now,
                "operator-1",
                "incident review",
                "SHA-256",
                "deadbeef",
                "SHA256withRSA",
                "c2lnbmF0dXJl",
                "cHVibGljS2V5");
        return new EvidenceBundle(manifest, content);
    }
}
