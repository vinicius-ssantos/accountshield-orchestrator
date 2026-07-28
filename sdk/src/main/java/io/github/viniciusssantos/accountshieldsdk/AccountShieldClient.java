package io.github.viniciusssantos.accountshieldsdk;

import io.github.viniciusssantos.accountshieldsdk.internal.ProblemDetailsParser;
import io.github.viniciusssantos.accountshieldsdk.model.ChallengeVerificationRequest;
import io.github.viniciusssantos.accountshieldsdk.model.ChallengeVerificationResponse;
import io.github.viniciusssantos.accountshieldsdk.model.ProblemDetails;
import io.github.viniciusssantos.accountshieldsdk.model.ProtectionDecisionRequest;
import io.github.viniciusssantos.accountshieldsdk.model.ProtectionDecisionResponse;
import io.github.viniciusssantos.accountshieldsdk.model.RecoveryResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Typed synchronous client for the AccountShield protection/challenge/recovery API. Built on the
 * JDK's own {@link java.net.http.HttpClient} -- no HTTP-client framework dependency -- plus Jackson
 * for JSON, matching the server's own Jackson 3.x stack (see {@code sdk/pom.xml}'s comment). Has no
 * dependency on any {@code io.github.viniciusssantos.accountshield.*} server package: every request/
 * response type here is a hand-written, independently-maintained copy of the real wire contract
 * (see {@code SdkContractVerificationTest} on the server for the live round-trip proof).
 */
public final class AccountShieldClient {

    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final String PROBLEM_JSON_CONTENT_TYPE = "application/problem+json";

    private final URI baseUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;
    private final RetryPolicy retryPolicy;
    private final Supplier<String> traceparentSupplier;
    private final Supplier<String> bearerTokenSupplier;

    private AccountShieldClient(Builder builder) {
        this.baseUri = builder.baseUri;
        this.requestTimeout = builder.requestTimeout;
        this.retryPolicy = builder.retryPolicy;
        this.traceparentSupplier = builder.traceparentSupplier;
        this.bearerTokenSupplier = builder.bearerTokenSupplier;
        this.objectMapper = JsonMapper.builder().build();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(builder.connectTimeout)
                .build();
    }

    public static Builder builder(URI baseUri) {
        return new Builder(baseUri);
    }

    /**
     * Safe to retry only when {@code request.idempotencyKey()} is set -- the server's idempotency
     * store (ADR 0018) guarantees a retried call with the same key returns the original decision
     * rather than creating a duplicate. A request with no idempotency key is never retried, even on
     * a network failure, since the SDK cannot know whether the original attempt's side effects
     * already landed.
     */
    public ProtectionDecisionResponse decideProtection(ProtectionDecisionRequest request, String correlationId) {
        boolean safeToRetry = request.idempotencyKey() != null && !request.idempotencyKey().isBlank();
        return execute("POST", "/api/v1/protection-decisions", request, ProtectionDecisionResponse.class,
                201, safeToRetry, correlationId);
    }

    public ProtectionDecisionResponse decideProtection(ProtectionDecisionRequest request) {
        return decideProtection(request, null);
    }

    /**
     * Never retried: each verification attempt consumes the challenge's own attempt budget
     * (server-side {@code ChallengeApplicationService.DEFAULT_MAX_ATTEMPTS}) -- retrying a timed-out
     * request could exhaust a legitimate user's remaining attempts for a call that may have already
     * succeeded.
     */
    public ChallengeVerificationResponse verifyChallenge(UUID challengeId, ChallengeVerificationRequest request, String correlationId) {
        return execute("POST", "/api/v1/challenges/" + challengeId + "/verify", request,
                ChallengeVerificationResponse.class, 200, false, correlationId);
    }

    /**
     * Safe to retry: re-initiating with the same {@code authorizationId} returns the existing flow
     * rather than creating a second one (server-side "equivalent initiation retry" behavior).
     */
    public RecoveryResponse initiateRecovery(UUID authorizationId, String correlationId) {
        return execute("POST", "/api/v1/recovery", new InitiateRecoveryBody(authorizationId),
                RecoveryResponse.class, 200, true, correlationId);
    }

    /** Not retried by default: this SDK does not assert confirm-identity's idempotency semantics. */
    public RecoveryResponse confirmRecoveryIdentity(UUID recoveryId, UUID challengeId, String correlationId) {
        return execute("POST", "/api/v1/recovery/" + recoveryId + "/confirm-identity",
                new ConfirmIdentityBody(challengeId), RecoveryResponse.class, 200, false, correlationId);
    }

    /** Not retried by default: this SDK does not assert completion's idempotency semantics. */
    public RecoveryResponse completeRecovery(UUID recoveryId, String correlationId) {
        return execute("POST", "/api/v1/recovery/" + recoveryId + "/complete", null,
                RecoveryResponse.class, 200, false, correlationId);
    }

    private <T> T execute(
            String method, String path, Object requestBody, Class<T> responseType,
            int expectedStatus, boolean safeToRetry, String correlationId) {
        String resolvedCorrelationId = resolveCorrelationId(correlationId);
        String jsonBody = requestBody == null ? "" : objectMapper.writeValueAsString(requestBody);

        int attempt = 0;
        while (true) {
            attempt++;
            HttpResponse<String> response;
            try {
                response = httpClient.send(buildRequest(method, path, jsonBody, resolvedCorrelationId),
                        HttpResponse.BodyHandlers.ofString());
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (retryPolicy.shouldRetryAfterFailure(attempt, safeToRetry, true, -1)) {
                    sleep(retryPolicy.delayBeforeAttempt(attempt));
                    continue;
                }
                throw new AccountShieldClientException("request failed: " + method + " " + path, exception);
            }

            if (response.statusCode() == expectedStatus) {
                return response.body().isBlank() ? null : objectMapper.readValue(response.body(), responseType);
            }

            if (retryPolicy.shouldRetryAfterFailure(attempt, safeToRetry, false, response.statusCode())) {
                sleep(retryPolicy.delayBeforeAttempt(attempt));
                continue;
            }

            throw toException(response);
        }
    }

    private HttpRequest buildRequest(String method, String path, String jsonBody, String correlationId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header(CORRELATION_ID_HEADER, correlationId);
        // Distributed tracing integration: propagates a caller-supplied W3C Trace Context header
        // (https://www.w3.org/TR/trace-context/) rather than depending on an OpenTelemetry SDK --
        // the SDK stays framework-agnostic while still letting a consumer that already has its own
        // tracing stack (e.g. via the server's own OTLP export, ADR 0030) link its spans across the
        // call.
        if (traceparentSupplier != null) {
            String traceparent = traceparentSupplier.get();
            if (traceparent != null && !traceparent.isBlank()) {
                builder.header("traceparent", traceparent);
            }
        }
        // Most endpoints (protection decisions, recovery initiation/confirm/complete, challenge
        // verification) are behind this server's JWT resource server (ADR 0011) -- a bearer token
        // supplier lets a long-lived client keep using a refreshed token without rebuilding the
        // client.
        if (bearerTokenSupplier != null) {
            String token = bearerTokenSupplier.get();
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
        }
        HttpRequest.BodyPublisher body = jsonBody.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody);
        return builder.method(method, body).build();
    }

    private RuntimeException toException(HttpResponse<String> response) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (contentType.contains(PROBLEM_JSON_CONTENT_TYPE) && !response.body().isBlank()) {
            ProblemDetails problem = ProblemDetailsParser.parse(objectMapper, response.body());
            return new AccountShieldApiException(response.statusCode(), problem);
        }
        return new AccountShieldClientException(
                "unexpected response status " + response.statusCode() + ": " + truncate(response.body()));
    }

    private String resolveCorrelationId(String clientSupplied) {
        if (clientSupplied != null && SAFE_CORRELATION_ID.matcher(clientSupplied).matches()) {
            return clientSupplied;
        }
        return UUID.randomUUID().toString();
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private String truncate(String text) {
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }

    private record InitiateRecoveryBody(UUID authorizationId) {
    }

    private record ConfirmIdentityBody(UUID challengeId) {
    }

    public static final class Builder {
        private final URI baseUri;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(10);
        private RetryPolicy retryPolicy = RetryPolicy.defaultPolicy();
        private Supplier<String> traceparentSupplier;
        private Supplier<String> bearerTokenSupplier;

        private Builder(URI baseUri) {
            this.baseUri = baseUri;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        /** Supplies a W3C {@code traceparent} header value per request; omitted when null or blank. */
        public Builder traceparentSupplier(Supplier<String> traceparentSupplier) {
            this.traceparentSupplier = traceparentSupplier;
            return this;
        }

        /**
         * Supplies an {@code Authorization: Bearer <token>} header per request; omitted when null
         * or blank. Required for every endpoint except {@code /demo/webhook-receiver} (see
         * {@code SecurityConfig} on the server).
         */
        public Builder bearerTokenSupplier(Supplier<String> bearerTokenSupplier) {
            this.bearerTokenSupplier = bearerTokenSupplier;
            return this;
        }

        /** Convenience for a fixed token that never changes for this client's lifetime. */
        public Builder bearerToken(String token) {
            this.bearerTokenSupplier = () -> token;
            return this;
        }

        public AccountShieldClient build() {
            return new AccountShieldClient(this);
        }
    }
}
