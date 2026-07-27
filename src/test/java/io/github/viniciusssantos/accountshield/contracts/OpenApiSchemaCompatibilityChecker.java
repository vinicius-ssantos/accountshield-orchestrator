package io.github.viniciusssantos.accountshield.contracts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Structural compatibility checker for OpenAPI documents represented as plain Jackson
 * Map/List/scalar trees (the same shape {@code ObjectMapper.readValue(json, Map.class)} already
 * produces elsewhere in this codebase, e.g. {@code JdbcDecisionTraceQuery.parseContext}). This is
 * deliberately narrow: it only flags the categories issue #52 names -- endpoint/method removals,
 * removed fields, incompatible type changes, new required fields, and enum value removals --
 * rather than adopting a general-purpose third-party OpenAPI-diff library, to avoid an unverified
 * new dependency's transitive-version risk against this project's Spring Boot 4 / Java 25 stack
 * (see ADR 0029's Alternatives considered).
 *
 * <p>Recursion into nested schemas ({@code properties}, {@code items}, {@code $ref}) is bounded
 * by a fixed depth rather than a ref-cycle-tracking visited set: a shared visited set would risk
 * silently skipping a legitimately repeated {@code $ref} reached from a second, unrelated property
 * path, which would be a missed (not merely skipped) violation. A depth cap accepts a documented,
 * far simpler limitation instead -- pathologically deep or cyclic schemas stop being checked past
 * the cap, rather than a subtle false-negative risk that could hide a real one.</p>
 */
public final class OpenApiSchemaCompatibilityChecker {

    private static final int MAX_DEPTH = 25;

    private OpenApiSchemaCompatibilityChecker() {
    }

    public static List<String> compare(Map<String, Object> baseline, Map<String, Object> current) {
        List<String> violations = new ArrayList<>();
        compareEndpoints(asMap(baseline.get("paths")), asMap(current.get("paths")), violations);

        Map<String, Object> baselineSchemas = asMap(pathTo(baseline, "components", "schemas"));
        Map<String, Object> currentSchemas = asMap(pathTo(current, "components", "schemas"));
        compareSchemas(baselineSchemas, currentSchemas, violations);
        return violations;
    }

    private static void compareEndpoints(
            Map<String, Object> baselinePaths, Map<String, Object> currentPaths, List<String> violations) {
        if (baselinePaths == null) {
            return;
        }
        for (String path : baselinePaths.keySet()) {
            Map<String, Object> baselineMethods = asMap(baselinePaths.get(path));
            Map<String, Object> currentMethods = currentPaths == null ? null : asMap(currentPaths.get(path));
            if (currentMethods == null) {
                violations.add("endpoint removed: " + path);
                continue;
            }
            if (baselineMethods == null) {
                continue;
            }
            for (String method : baselineMethods.keySet()) {
                if (!currentMethods.containsKey(method)) {
                    violations.add("endpoint removed: " + method.toUpperCase() + " " + path);
                }
            }
        }
    }

    private static void compareSchemas(
            Map<String, Object> baselineSchemas, Map<String, Object> currentSchemas, List<String> violations) {
        if (baselineSchemas == null) {
            return;
        }
        for (String name : baselineSchemas.keySet()) {
            Map<String, Object> currentSchema = currentSchemas == null ? null : asMap(currentSchemas.get(name));
            if (currentSchema == null) {
                violations.add("schema removed: #/components/schemas/" + name);
                continue;
            }
            compareSchemaNode(
                    "#/components/schemas/" + name, asMap(baselineSchemas.get(name)), currentSchema,
                    baselineSchemas, currentSchemas, 0, violations);
        }
    }

    private static void compareSchemaNode(
            String path, Map<String, Object> baseline, Map<String, Object> current,
            Map<String, Object> baselineSchemas, Map<String, Object> currentSchemas,
            int depth, List<String> violations) {
        if (baseline == null || depth > MAX_DEPTH) {
            return;
        }
        if (current == null) {
            violations.add("removed: " + path);
            return;
        }

        String baselineRef = asString(baseline.get("$ref"));
        if (baselineRef != null) {
            String currentRef = asString(current.get("$ref"));
            Map<String, Object> resolvedBaseline = resolveRef(baselineRef, baselineSchemas);
            Map<String, Object> resolvedCurrent = currentRef != null
                    ? resolveRef(currentRef, currentSchemas)
                    : current;
            compareSchemaNode(path, resolvedBaseline, resolvedCurrent, baselineSchemas, currentSchemas,
                    depth + 1, violations);
            return;
        }

        String baselineType = asString(baseline.get("type"));
        String currentType = asString(current.get("type"));
        if (baselineType != null && currentType != null && !baselineType.equals(currentType)) {
            violations.add("type changed at " + path + ": " + baselineType + " -> " + currentType);
        }

        List<Object> baselineEnum = asList(baseline.get("enum"));
        if (baselineEnum != null) {
            List<Object> currentEnum = asList(current.get("enum"));
            for (Object value : baselineEnum) {
                if (currentEnum == null || !currentEnum.contains(value)) {
                    violations.add("enum value removed at " + path + ": " + value);
                }
            }
        }

        List<Object> currentRequired = asList(current.get("required"));
        if (currentRequired != null) {
            List<Object> baselineRequired = asList(baseline.get("required"));
            for (Object name : currentRequired) {
                if (baselineRequired == null || !baselineRequired.contains(name)) {
                    violations.add("new required field at " + path + ": " + name);
                }
            }
        }

        Map<String, Object> baselineProps = asMap(baseline.get("properties"));
        if (baselineProps != null) {
            Map<String, Object> currentProps = asMap(current.get("properties"));
            for (String propName : baselineProps.keySet()) {
                Map<String, Object> currentPropSchema = currentProps == null ? null : asMap(currentProps.get(propName));
                if (currentPropSchema == null) {
                    violations.add("field removed at " + path + ": " + propName);
                    continue;
                }
                compareSchemaNode(path + "." + propName, asMap(baselineProps.get(propName)), currentPropSchema,
                        baselineSchemas, currentSchemas, depth + 1, violations);
            }
        }

        Map<String, Object> baselineItems = asMap(baseline.get("items"));
        if (baselineItems != null) {
            compareSchemaNode(path + "[]", baselineItems, asMap(current.get("items")),
                    baselineSchemas, currentSchemas, depth + 1, violations);
        }
    }

    private static Map<String, Object> resolveRef(String ref, Map<String, Object> schemas) {
        if (schemas == null) {
            return null;
        }
        String name = ref.substring(ref.lastIndexOf('/') + 1);
        return asMap(schemas.get(name));
    }

    private static Object pathTo(Map<String, Object> root, String... keys) {
        Object current = root;
        for (String key : keys) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<?, ?>) current).get(key);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }
}
