package io.github.viniciusssantos.accountshieldsdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.viniciusssantos.accountshieldsdk.model.NetworkRiskLevel;
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
}
