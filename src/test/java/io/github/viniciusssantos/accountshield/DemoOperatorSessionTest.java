package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgreSqlTestConfiguration.class)
class DemoOperatorSessionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtDecoder jwtDecoder;
    @Autowired private LocalJwtKeys localJwtKeys;

    @Autowired
    @Qualifier("decisionClock")
    private Clock clock;

    @Test
    void correctCredentialsIssueTokenWithConfiguredRoles() throws Exception {
        String token = issueToken("operator-1", "accountshield-demo-operator");

        Jwt jwt = jwtDecoder.decode(token);
        assertThat(jwt.getSubject()).isEqualTo("operator-1");
        assertThat(jwt.getClaimAsStringList(LocalJwtKeys.ROLES_CLAIM)).containsExactly("SECURITY_OPERATOR");
        assertThat(jwt.getExpiresAt()).isAfter(clock.instant());
    }

    @Test
    void wrongPasswordForKnownUsernameReturnsGenericUnauthorized() throws Exception {
        mockMvc.perform(post("/auth/session-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("operator-1", "not-the-real-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("The supplied username or password is not valid."));
    }

    @Test
    void unknownUsernameReturnsIdenticalUnauthorizedShapeAsWrongPassword() throws Exception {
        mockMvc.perform(post("/auth/session-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("no-such-operator", "whatever")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("The supplied username or password is not valid."));
    }

    @Test
    void malformedRequestBodyReturnsBadRequestNotServerError() throws Exception {
        mockMvc.perform(post("/auth/session-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"username\": \"\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION_TOKEN_REQUEST"));

        mockMvc.perform(post("/auth/session-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION_TOKEN_REQUEST"));
    }

    @Test
    void sessionTokenEndpointsAreReachableWithoutPriorAuthentication() throws Exception {
        // No AUTHENTICATION_REQUIRED for a malformed/failed attempt -- these routes must be
        // permitAll, since they are the login entry point itself.
        mockMvc.perform(post("/auth/session-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("operator-1", "wrong")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void refreshReissuesTokenWithSameSubjectAndRoles() throws Exception {
        String original = issueToken("admin-1", "accountshield-demo-admin");

        MvcResult result = mockMvc.perform(post("/auth/session-tokens/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + original))
                .andExpect(status().isOk())
                .andReturn();

        // Not asserting refreshed != original: iat/exp are second-granularity and RSA signing is
        // deterministic, so two calls landing in the same clock second legitimately produce byte-
        // identical tokens. What matters is that refresh reissues the same subject/roles.
        String refreshed = tokenFrom(result);

        Jwt refreshedJwt = jwtDecoder.decode(refreshed);
        assertThat(refreshedJwt.getSubject()).isEqualTo("admin-1");
        assertThat(refreshedJwt.getClaimAsStringList(LocalJwtKeys.ROLES_CLAIM)).containsExactly("POLICY_ADMIN");
    }

    @Test
    void refreshRejectsExpiredToken() throws Exception {
        Clock tenMinutesAgo = Clock.fixed(clock.instant().minus(Duration.ofMinutes(10)), clock.getZone());
        String expired =
                localJwtKeys.signToken("operator-1", List.of("SECURITY_OPERATOR"), Duration.ofSeconds(30), tenMinutesAgo);

        mockMvc.perform(post("/auth/session-tokens/refresh").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void refreshRejectsTamperedToken() throws Exception {
        String valid = issueToken("operator-1", "accountshield-demo-operator");
        String tampered = valid.substring(0, valid.length() - 4) + "abcd";

        mockMvc.perform(post("/auth/session-tokens/refresh").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void refreshRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/auth/session-tokens/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    private String issueToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/session-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return tokenFrom(result);
    }

    private String tokenFrom(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("token").asText();
    }

    private String credentials(String username, String password) {
        return """
                { "username": "%s", "password": "%s" }
                """.formatted(username, password);
    }
}
