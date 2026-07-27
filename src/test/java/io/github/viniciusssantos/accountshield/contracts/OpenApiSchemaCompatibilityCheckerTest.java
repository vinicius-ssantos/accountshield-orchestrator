package io.github.viniciusssantos.accountshield.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenApiSchemaCompatibilityCheckerTest {

    @Test
    void identicalDocumentsHaveNoViolations() {
        Map<String, Object> spec = baseSpec();

        assertThat(OpenApiSchemaCompatibilityChecker.compare(spec, deepCopy(spec))).isEmpty();
    }

    @Test
    void additiveChangesAreAllowed() {
        Map<String, Object> baseline = baseSpec();
        Map<String, Object> current = deepCopy(baseline);
        properties(current).put("newField", Map.of("type", "string"));
        paths(current).put("/api/v1/new-endpoint", Map.of("get", Map.of()));

        assertThat(OpenApiSchemaCompatibilityChecker.compare(baseline, current)).isEmpty();
    }

    @Test
    void detectsARemovedField() {
        Map<String, Object> baseline = baseSpec();
        Map<String, Object> current = deepCopy(baseline);
        properties(current).remove("outcome");

        List<String> violations = OpenApiSchemaCompatibilityChecker.compare(baseline, current);

        assertThat(violations).anyMatch(v -> v.contains("field removed") && v.contains("outcome"));
    }

    @Test
    void detectsAnIncompatibleTypeChange() {
        Map<String, Object> baseline = baseSpec();
        Map<String, Object> current = deepCopy(baseline);
        properties(current).put("riskScore", Map.of("type", "string"));

        List<String> violations = OpenApiSchemaCompatibilityChecker.compare(baseline, current);

        assertThat(violations).anyMatch(v -> v.contains("type changed") && v.contains("riskScore"));
    }

    @Test
    void detectsANewlyRequiredField() {
        Map<String, Object> baseline = baseSpec();
        Map<String, Object> current = deepCopy(baseline);
        schema(current).put("required", List.of("outcome", "riskScore"));

        List<String> violations = OpenApiSchemaCompatibilityChecker.compare(baseline, current);

        assertThat(violations).anyMatch(v -> v.contains("new required field") && v.contains("riskScore"));
    }

    @Test
    void relaxingAPreviouslyRequiredFieldIsAllowed() {
        Map<String, Object> baseline = baseSpec();
        schema(baseline).put("required", List.of("outcome", "riskScore"));
        Map<String, Object> current = deepCopy(baseline);
        schema(current).put("required", List.of("outcome"));

        List<String> violations = OpenApiSchemaCompatibilityChecker.compare(baseline, current);

        assertThat(violations).isEmpty();
    }

    @Test
    void detectsAnEnumValueRemoval() {
        Map<String, Object> baseline = baseSpec();
        properties(baseline).put("outcome", Map.of("type", "string", "enum", List.of("ALLOW", "TEMPORARILY_BLOCK")));
        Map<String, Object> current = deepCopy(baseline);
        properties(current).put("outcome", Map.of("type", "string", "enum", List.of("ALLOW")));

        List<String> violations = OpenApiSchemaCompatibilityChecker.compare(baseline, current);

        assertThat(violations).anyMatch(v -> v.contains("enum value removed") && v.contains("TEMPORARILY_BLOCK"));
    }

    @Test
    void addingAnEnumValueIsAllowed() {
        Map<String, Object> baseline = baseSpec();
        properties(baseline).put("outcome", Map.of("type", "string", "enum", List.of("ALLOW")));
        Map<String, Object> current = deepCopy(baseline);
        properties(current).put("outcome", Map.of("type", "string", "enum", List.of("ALLOW", "TEMPORARILY_BLOCK")));

        assertThat(OpenApiSchemaCompatibilityChecker.compare(baseline, current)).isEmpty();
    }

    @Test
    void detectsARemovedEndpoint() {
        Map<String, Object> baseline = baseSpec();
        Map<String, Object> current = deepCopy(baseline);
        paths(current).remove("/api/v1/decisions");

        List<String> violations = OpenApiSchemaCompatibilityChecker.compare(baseline, current);

        assertThat(violations).anyMatch(v -> v.contains("endpoint removed") && v.contains("/api/v1/decisions"));
    }

    @Test
    void detectsARemovedMethodOnAnExistingEndpoint() {
        Map<String, Object> baseline = baseSpec();
        Map<String, Object> current = deepCopy(baseline);
        pathMethods(current, "/api/v1/decisions").remove("post");

        List<String> violations = OpenApiSchemaCompatibilityChecker.compare(baseline, current);

        assertThat(violations).anyMatch(v -> v.contains("endpoint removed") && v.contains("POST"));
    }

    @Test
    void resolvesRefsWhenComparingNestedSchemas() {
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("paths", new LinkedHashMap<>());
        Map<String, Object> baselineComponents = new LinkedHashMap<>();
        Map<String, Object> baselineSchemas = new LinkedHashMap<>();
        baselineSchemas.put("Decision", Map.of("$ref", "#/components/schemas/DecisionImpl"));
        baselineSchemas.put("DecisionImpl", Map.of("type", "object",
                "properties", Map.of("outcome", Map.of("type", "string"))));
        baselineComponents.put("schemas", baselineSchemas);
        baseline.put("components", baselineComponents);

        Map<String, Object> current = deepCopy(baseline);
        Map<String, Object> currentSchemas = asMap(asMap(current.get("components")).get("schemas"));
        currentSchemas.put("DecisionImpl", Map.of("type", "object", "properties", Map.of()));

        List<String> violations = OpenApiSchemaCompatibilityChecker.compare(baseline, current);

        assertThat(violations).anyMatch(v -> v.contains("field removed") && v.contains("outcome"));
    }

    private Map<String, Object> baseSpec() {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> get = new LinkedHashMap<>();
        Map<String, Object> post = new LinkedHashMap<>();
        Map<String, Object> methods = new LinkedHashMap<>();
        methods.put("get", get);
        methods.put("post", post);
        Map<String, Object> paths = new LinkedHashMap<>();
        paths.put("/api/v1/decisions", methods);
        root.put("paths", paths);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("outcome", new LinkedHashMap<>(Map.of("type", "string")));
        properties.put("riskScore", new LinkedHashMap<>(Map.of("type", "integer")));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        Map<String, Object> schemas = new LinkedHashMap<>();
        schemas.put("DecisionResponse", schema);
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("schemas", schemas);
        root.put("components", components);

        return root;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schema(Map<String, Object> spec) {
        return (Map<String, Object>) asMap(asMap(spec.get("components")).get("schemas")).get("DecisionResponse");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(Map<String, Object> spec) {
        return (Map<String, Object>) schema(spec).get("properties");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paths(Map<String, Object> spec) {
        return (Map<String, Object>) spec.get("paths");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pathMethods(Map<String, Object> spec, String path) {
        return (Map<String, Object>) paths(spec).get(path);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                copy.put(entry.getKey(), deepCopy((Map<String, Object>) value));
            } else if (value instanceof List) {
                copy.put(entry.getKey(), new java.util.ArrayList<>((List<Object>) value));
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return copy;
    }
}
