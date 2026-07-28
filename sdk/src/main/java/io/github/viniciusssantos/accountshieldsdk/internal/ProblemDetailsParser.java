package io.github.viniciusssantos.accountshieldsdk.internal;

import io.github.viniciusssantos.accountshieldsdk.model.ProblemDetails;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses an {@code application/problem+json} body into {@link ProblemDetails}: known RFC 9457
 * fields are typed, every other top-level property (e.g. {@code retryAfter}, {@code observedAt})
 * falls through into {@link ProblemDetails#extensions()}. A hand-written parse rather than a
 * Jackson-annotated record, since there is no clean, low-risk way to get "typed known fields +
 * catch-all map for the rest" out of record/builder deserialization.
 */
public final class ProblemDetailsParser {

    private static final Set<String> KNOWN_FIELDS =
            Set.of("type", "title", "status", "detail", "instance", "code");

    private ProblemDetailsParser() {
    }

    public static ProblemDetails parse(ObjectMapper objectMapper, String rawBody) {
        JsonNode root = objectMapper.readTree(rawBody);
        Map<String, Object> extensions = new LinkedHashMap<>();
        root.properties().forEach(entry -> {
            if (!KNOWN_FIELDS.contains(entry.getKey())) {
                extensions.put(entry.getKey(), objectMapper.convertValue(entry.getValue(), Object.class));
            }
        });
        return new ProblemDetails(
                textOrNull(root, "type"),
                textOrNull(root, "title"),
                root.has("status") ? root.get("status").asInt() : null,
                textOrNull(root, "detail"),
                textOrNull(root, "instance"),
                textOrNull(root, "code"),
                extensions);
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asString();
    }
}
