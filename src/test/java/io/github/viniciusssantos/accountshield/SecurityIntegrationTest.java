package io.github.viniciusssantos.accountshield;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgreSqlTestConfiguration.class)
class SecurityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private LocalJwtKeys localJwtKeys;

    @Test
    void healthProbeIsReachableAnonymously() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void createDraftPolicyRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPolicyPayload("sec-anon-" + UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void createDraftPolicyRejectsWrongRole() throws Exception {
        mockMvc.perform(post("/api/v1/policies")
                        .header(HttpHeaders.AUTHORIZATION, bearer("client-1", "PROTECTION_CLIENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPolicyPayload("sec-wrong-role-" + UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_PRIVILEGES"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void createDraftPolicySucceedsForPolicyAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/policies")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin-1", "POLICY_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPolicyPayload("sec-admin-" + UUID.randomUUID())))
                .andExpect(status().isCreated());
    }

    @Test
    void reviewRecoveryRejectsWrongRoleBeforeReachingBusinessLogic() throws Exception {
        mockMvc.perform(post("/api/v1/recovery/" + UUID.randomUUID() + "/review")
                        .header(HttpHeaders.AUTHORIZATION, bearer("client-1", "PROTECTION_CLIENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "decision": "APPROVE" }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void reviewRecoveryPassesAuthorizationForSecurityOperator() throws Exception {
        mockMvc.perform(post("/api/v1/recovery/" + UUID.randomUUID() + "/review")
                        .header(HttpHeaders.AUTHORIZATION, bearer("operator-1", "SECURITY_OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "decision": "APPROVE" }
                                """))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401 || status == 403) {
                        throw new AssertionError("expected authorization to pass, got status " + status);
                    }
                });
    }

    @Test
    void prometheusRequiresObservabilityReaderRole() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/prometheus")
                        .header(HttpHeaders.AUTHORIZATION, bearer("reader-1", "OBSERVABILITY_READER")))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401 || status == 403) {
                        throw new AssertionError("expected authorization to pass, got status " + status);
                    }
                });
    }

    private String bearer(String subject, String role) {
        return "Bearer " + localJwtKeys.signToken(subject, List.of(role), Duration.ofMinutes(5), Clock.systemUTC());
    }

    private String validPolicyPayload(String policyKey) {
        return """
                { "policyKey": "%s", "version": "1.0.0", "allowMaxScore": 25, "stepUpMaxScore": 65 }
                """.formatted(policyKey);
    }
}
