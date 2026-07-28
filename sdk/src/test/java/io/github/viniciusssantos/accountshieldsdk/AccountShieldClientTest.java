package io.github.viniciusssantos.accountshieldsdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.viniciusssantos.accountshieldsdk.model.EvidenceVerificationResult;
import io.github.viniciusssantos.accountshieldsdk.model.NetworkRiskLevel;
import io.github.viniciusssantos.accountshieldsdk.model.PolicyAnalysisRequest;
import io.github.viniciusssantos.accountshieldsdk.model.PolicyAnalysisResult;
import io.github.viniciusssantos.accountshieldsdk.model.PolicyImpactReport;
import io.github.viniciusssantos.accountshieldsdk.model.ProtectionDecisionRequest;
import io.github.viniciusssantos.accountshieldsdk.model.ProtectionDecisionResponse;
import io.github.viniciusssantos.accountshieldsdk.model.ProtectionEventType;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link AccountShieldClient} against a real (fake, but real HTTP) local server -- no
 * mocking framework, a plain {@link HttpServer} -- rather than against the real AccountShield
 * server, which the SDK module cannot depend on (see {@code sdk/pom.xml}). The real-server round
 * trip is proven separately by {@code SdkContractVerificationTest} on the server side.
 */
class AccountShieldClientTest {

    private HttpServer server;
    private URI baseUri;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        baseUri = URI.create("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void decideProtectionParsesARealResponseBody() {
        server.createContext("/api/v1/protection-decisions", exchange -> {
            String body = "{\"decisionId\":\"11111111-1111-1111-1111-111111111111\","
                    + "\"protectionRequestId\":\"22222222-2222-2222-2222-222222222222\","
                    + "\"recoveryAuthorizationId\":null,\"outcome\":\"ALLOW\",\"riskScore\":15,"
                    + "\"riskBand\":\"LOW\",\"algorithmVersion\":\"risk-v1\",\"policyKey\":\"account-protection-default\","
                    + "\"policyVersion\":\"1.1.0\",\"reasons\":[],\"decidedAt\":\"2026-01-01T00:00:00Z\","
                    + "\"challenge\":null,\"degraded\":false,\"degradationReason\":null}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AccountShieldClient client = AccountShieldClient.builder(baseUri).build();
        ProtectionDecisionResponse response = client.decideProtection(
                ProtectionDecisionRequest.builder("alice@example.test", ProtectionEventType.LOGIN_ATTEMPT)
                        .networkRiskLevel(NetworkRiskLevel.LOW)
                        .build());

        assertThat(response.outcome().name()).isEqualTo("ALLOW");
        assertThat(response.riskScore()).isEqualTo(15);
        assertThat(response.reasons()).isEmpty();
    }

    @Test
    void parsesAProblemDetailsErrorResponse() {
        server.createContext("/api/v1/protection-decisions", exchange -> {
            String body = "{\"type\":\"urn:accountshield:problem:invalid-protection-request\","
                    + "\"title\":\"Invalid request\",\"status\":400,\"detail\":\"accountReference must not be blank\","
                    + "\"instance\":null,\"code\":\"INVALID_PROTECTION_REQUEST\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(400, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AccountShieldClient client = AccountShieldClient.builder(baseUri).build();

        assertThatThrownBy(() -> client.decideProtection(
                ProtectionDecisionRequest.builder("alice@example.test", ProtectionEventType.LOGIN_ATTEMPT).build()))
                .isInstanceOf(AccountShieldApiException.class)
                .satisfies(exception -> {
                    AccountShieldApiException apiException = (AccountShieldApiException) exception;
                    assertThat(apiException.httpStatus()).isEqualTo(400);
                    assertThat(apiException.problem().code()).isEqualTo("INVALID_PROTECTION_REQUEST");
                });
    }

    @Test
    void retriesASafeIdempotentDecisionOnTransientServerErrorsAndEventuallySucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/api/v1/protection-decisions", exchange -> {
            if (attempts.incrementAndGet() < 3) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            String body = "{\"decisionId\":\"11111111-1111-1111-1111-111111111111\","
                    + "\"protectionRequestId\":\"22222222-2222-2222-2222-222222222222\","
                    + "\"recoveryAuthorizationId\":null,\"outcome\":\"ALLOW\",\"riskScore\":0,"
                    + "\"riskBand\":\"LOW\",\"algorithmVersion\":\"risk-v1\",\"policyKey\":\"account-protection-default\","
                    + "\"policyVersion\":\"1.1.0\",\"reasons\":[],\"decidedAt\":\"2026-01-01T00:00:00Z\","
                    + "\"challenge\":null,\"degraded\":false,\"degradationReason\":null}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AccountShieldClient client = AccountShieldClient.builder(baseUri)
                .retryPolicy(new RetryPolicy(5, Duration.ofMillis(1), Duration.ofMillis(5)))
                .build();
        ProtectionDecisionResponse response = client.decideProtection(
                ProtectionDecisionRequest.builder("alice@example.test", ProtectionEventType.LOGIN_ATTEMPT)
                        .idempotencyKey("idem-retry-test")
                        .build());

        assertThat(response.riskScore()).isZero();
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void neverRetriesADecisionWithoutAnIdempotencyKeyEvenOnATransientServerError() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/api/v1/protection-decisions", exchange -> {
            attempts.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        AccountShieldClient client = AccountShieldClient.builder(baseUri)
                .retryPolicy(new RetryPolicy(5, Duration.ofMillis(1), Duration.ofMillis(5)))
                .build();

        assertThatThrownBy(() -> client.decideProtection(
                ProtectionDecisionRequest.builder("alice@example.test", ProtectionEventType.LOGIN_ATTEMPT).build()))
                .isInstanceOf(AccountShieldClientException.class);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void sendsABearerTokenHeaderWhenConfigured() {
        List<String> seenAuthHeaders = new java.util.ArrayList<>();
        server.createContext("/api/v1/protection-decisions", exchange -> {
            seenAuthHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();

        AccountShieldClient client = AccountShieldClient.builder(baseUri)
                .retryPolicy(RetryPolicy.noRetries())
                .bearerToken("my-jwt-token")
                .build();
        assertThatThrownBy(() -> client.decideProtection(
                ProtectionDecisionRequest.builder("alice@example.test", ProtectionEventType.LOGIN_ATTEMPT).build()))
                .isInstanceOf(AccountShieldClientException.class);

        assertThat(seenAuthHeaders).containsExactly("Bearer my-jwt-token");
    }

    @Test
    void sendsACorrelationIdHeaderOnEveryRequest() {
        List<String> seenHeaders = new java.util.ArrayList<>();
        server.createContext("/api/v1/protection-decisions", exchange -> {
            seenHeaders.add(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();

        AccountShieldClient client = AccountShieldClient.builder(baseUri)
                .retryPolicy(RetryPolicy.noRetries())
                .build();
        assertThatThrownBy(() -> client.decideProtection(
                ProtectionDecisionRequest.builder("alice@example.test", ProtectionEventType.LOGIN_ATTEMPT).build(),
                "my-correlation-id"))
                .isInstanceOf(AccountShieldClientException.class);

        assertThat(seenHeaders).containsExactly("my-correlation-id");
    }

    @Test
    void analyzePolicyParsesARealAnalysisResponse() {
        server.createContext("/api/v1/policies/analyze", exchange -> {
            String body = "{\"analyzerVersion\":\"policy-analyzer-1.0\",\"diagnostics\":["
                    + "{\"code\":\"STEP_UP_BAND_SHADOWED\",\"severity\":\"WARNING\",\"path\":\"stepUpMaxScore\","
                    + "\"message\":\"shadowed\"}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AccountShieldClient client = AccountShieldClient.builder(baseUri).build();
        PolicyAnalysisResult result = client.analyzePolicy(
                new PolicyAnalysisRequest(29, 69, 89), null);

        assertThat(result.analyzerVersion()).isEqualTo("policy-analyzer-1.0");
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.diagnostics()).hasSize(1);
        assertThat(result.diagnostics().get(0).code()).isEqualTo("STEP_UP_BAND_SHADOWED");
    }

    @Test
    void analyzePolicyImpactSendsQueryParametersAndParsesTheResponse() {
        List<String> seenQueries = new java.util.ArrayList<>();
        server.createContext("/api/v1/simulation/policy-impact", exchange -> {
            seenQueries.add(exchange.getRequestURI().getQuery());
            String body = "{\"policyKey\":\"account-protection-default\",\"candidatePolicyVersion\":\"1.2.0\","
                    + "\"originalPolicyVersionsObserved\":[\"1.1.0\"],\"algorithmVersionsObserved\":[\"risk-v1\"],"
                    + "\"totalDecisions\":10,\"divergentDecisionsCount\":1,\"divergencePercentage\":10.0,"
                    + "\"maxDivergencePercentageThreshold\":5.0,\"exceedsDivergenceThreshold\":true,"
                    + "\"transitionMatrix\":{\"ALLOW\":{\"ALLOW\":9,\"REQUIRE_STEP_UP\":1}},"
                    + "\"impactByEventType\":{},\"impactByRiskBand\":{},\"divergentDecisions\":[]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AccountShieldClient client = AccountShieldClient.builder(baseUri).build();
        PolicyImpactReport report = client.analyzePolicyImpact(
                "account-protection-default", "1.2.0", 5000, null);

        assertThat(seenQueries).containsExactly(
                "policyKey=account-protection-default&candidatePolicyVersion=1.2.0&maxSamples=5000");
        assertThat(report.exceedsDivergenceThreshold()).isTrue();
        assertThat(report.divergentDecisionsCount()).isEqualTo(1);
        assertThat(report.transitionMatrix().get("ALLOW").get("REQUIRE_STEP_UP")).isEqualTo(1L);
    }

    @Test
    void verifyEvidenceBundleSendsTheRawBodyUnmodifiedAndParsesTheResult() {
        List<String> seenBodies = new java.util.ArrayList<>();
        String rawBundle = "{\"manifest\":{\"bundleSchemaVersion\":\"evidence-bundle-1.0\"},\"content\":{}}";
        server.createContext("/api/v1/evidence/verify", exchange -> {
            seenBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = "{\"valid\":true,\"problems\":[]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AccountShieldClient client = AccountShieldClient.builder(baseUri).build();
        EvidenceVerificationResult result = client.verifyEvidenceBundle(rawBundle, null);

        assertThat(seenBodies).containsExactly(rawBundle);
        assertThat(result.valid()).isTrue();
        assertThat(result.problems()).isEmpty();
    }
}
