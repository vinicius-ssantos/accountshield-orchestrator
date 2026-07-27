package io.github.viniciusssantos.accountshield.audit.internal.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.viniciusssantos.accountshield.audit.AuditChainRootHash;
import io.github.viniciusssantos.accountshield.audit.AuditChainVerificationResult;
import io.github.viniciusssantos.accountshield.audit.AuditChainVerificationService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuditChainControllerTest {

    private final AuditChainVerificationService auditChainVerificationService = mock(AuditChainVerificationService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuditChainController(auditChainVerificationService))
                .setControllerAdvice(new AuditChainProblemHandler())
                .build();
    }

    @Test
    void verifyReturnsTheServiceResult() throws Exception {
        when(auditChainVerificationService.verifyRange(1, 5))
                .thenReturn(new AuditChainVerificationResult(5, true, List.of()));

        mockMvc.perform(get("/api/v1/audit/chain/verify").param("from", "1").param("to", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.recordsChecked").value(5));
    }

    @Test
    void verifyReturns400OnInvalidRange() throws Exception {
        when(auditChainVerificationService.verifyRange(5, 1))
                .thenThrow(new IllegalArgumentException("invalid range: [5, 1]"));

        mockMvc.perform(get("/api/v1/audit/chain/verify").param("from", "5").param("to", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUDIT_CHAIN_INVALID_RANGE"));
    }

    @Test
    void rootHashReturnsTheCurrentTip() throws Exception {
        when(auditChainVerificationService.currentRootHash())
                .thenReturn(Optional.of(new AuditChainRootHash(42, "abcd1234", Instant.parse("2026-01-01T00:00:00Z"))));

        mockMvc.perform(get("/api/v1/audit/chain/root-hash"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainSequence").value(42))
                .andExpect(jsonPath("$.recordHash").value("abcd1234"));
    }

    @Test
    void rootHashReturns404WhenChainIsEmpty() throws Exception {
        when(auditChainVerificationService.currentRootHash()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/audit/chain/root-hash"))
                .andExpect(status().isNotFound());
    }
}
