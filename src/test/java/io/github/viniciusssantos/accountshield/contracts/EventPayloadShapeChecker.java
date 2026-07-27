package io.github.viniciusssantos.accountshield.contracts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Structural diff between two integration-event payload fixtures, represented as plain Jackson
 * Map/List/scalar trees. Unlike {@link OpenApiSchemaCompatibilityChecker}, these trees are actual
 * DATA (a real serialized fixture), not a JSON Schema -- there is no {@code required}/{@code enum}
 * keyword to read, so only two things are checked: a field present in the baseline fixture that is
 * missing from the current one (removed field), and a field whose JSON kind (object/array/string/
 * boolean/number/null) changed between the two (incompatible type change). New fields in the
 * current fixture not present in the baseline are additive and never flagged.
 *
 * <p>Enum-value removals for the domain enums serialized into event payloads are checked
 * separately by {@code DomainEnumCompatibilityTest}, since a plain string field in a data fixture
 * does not self-document its allowed value set the way an OpenAPI schema's {@code enum} keyword
 * does.</p>
 */
public final class EventPayloadShapeChecker {

    private EventPayloadShapeChecker() {
    }

    public static List<String> compare(Map<String, Object> baseline, Map<String, Object> current) {
        List<String> violations = new ArrayList<>();
        compareValue("$", baseline, current, violations);
        return violations;
    }

    private static void compareValue(String path, Object baseline, Object current, List<String> violations) {
        if (baseline == null) {
            return;
        }
        if (current == null) {
            violations.add("removed: " + path);
            return;
        }

        String baselineKind = kindOf(baseline);
        String currentKind = kindOf(current);
        if (!baselineKind.equals(currentKind)) {
            violations.add("type changed at " + path + ": " + baselineKind + " -> " + currentKind);
            return;
        }

        if (baseline instanceof Map<?, ?> baselineMap) {
            Map<?, ?> currentMap = (Map<?, ?>) current;
            for (Object key : baselineMap.keySet()) {
                if (!currentMap.containsKey(key)) {
                    violations.add("field removed at " + path + ": " + key);
                    continue;
                }
                compareValue(path + "." + key, baselineMap.get(key), currentMap.get(key), violations);
            }
        } else if (baseline instanceof List<?> baselineList) {
            List<?> currentList = (List<?>) current;
            if (!baselineList.isEmpty() && !currentList.isEmpty()) {
                compareValue(path + "[]", baselineList.get(0), currentList.get(0), violations);
            }
        }
    }

    private static String kindOf(Object value) {
        if (value instanceof Map) {
            return "object";
        }
        if (value instanceof List) {
            return "array";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number) {
            return "number";
        }
        return "null";
    }
}
