package io.github.viniciusssantos.accountshieldcli;

import io.github.viniciusssantos.accountshieldsdk.AccountShieldClient;
import java.net.URI;
import picocli.CommandLine.Option;

/**
 * Options shared by every leaf command: where the server is, how to authenticate, whether to
 * emit JSON, and an optional caller-supplied correlation ID (issue #56: "preserve correlation
 * IDs"). Bound as a {@code @Mixin} rather than chased up through {@code @ParentCommand} levels,
 * since some leaf commands are nested two levels deep (e.g. {@code scenario run}).
 */
public final class CommonOptions {

    @Option(names = "--base-url", description = "AccountShield base URL "
            + "(default: $ACCOUNTSHIELD_BASE_URL env var, or http://localhost:8080)")
    private String baseUrl;

    @Option(names = "--token", description = "Bearer token "
            + "(default: $ACCOUNTSHIELD_BEARER_TOKEN env var)")
    private String token;

    @Option(names = "--correlation-id", description = "Correlation ID to send with this request "
            + "(default: a random one is generated and printed)")
    private String correlationId;

    @Option(names = "--json", description = "Emit machine-readable JSON instead of human-readable text")
    private boolean json;

    public boolean json() {
        return json;
    }

    public String correlationId() {
        return correlationId;
    }

    public String resolvedBaseUrl() {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl;
        }
        return System.getenv().getOrDefault("ACCOUNTSHIELD_BASE_URL", "http://localhost:8080");
    }

    public String resolvedToken() {
        if (token != null && !token.isBlank()) {
            return token;
        }
        return System.getenv("ACCOUNTSHIELD_BEARER_TOKEN");
    }

    public AccountShieldClient buildClient() {
        String resolvedToken = resolvedToken();
        AccountShieldClient.Builder builder = AccountShieldClient.builder(URI.create(resolvedBaseUrl()));
        if (resolvedToken != null && !resolvedToken.isBlank()) {
            builder.bearerToken(resolvedToken);
        }
        return builder.build();
    }
}
