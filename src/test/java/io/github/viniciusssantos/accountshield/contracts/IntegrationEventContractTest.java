package io.github.viniciusssantos.accountshield.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.viniciusssantos.accountshield.outbox.IntegrationEventEnvelope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Diffs the wire shape of every outbox integration event type against a checked-in baseline
 * fixture ({@code src/test/resources/contracts/events/<eventType>.json}) using
 * {@link EventPayloadShapeChecker}. All 6 baselines are committed; bootstrapping a fresh one (for
 * a genuinely new event type) requires {@code -Dcontracts.baseline.bootstrap=true} -- which
 * {@code ci.yml} never sets -- so a baseline missing in CI fails the test instead of silently
 * regenerating it (issue #152 / F-06, same treatment as {@link OpenApiCompatibilityTest}). See
 * ADR 0029.
 */
class IntegrationEventContractTest {

    private static final Path EVENTS_DIR = Path.of("src/test/resources/contracts/events");
    private static final ObjectMapper OBJECT_MAPPER = IntegrationEventFixtures.objectMapper();
    private static final boolean BOOTSTRAP_MODE = Boolean.getBoolean("contracts.baseline.bootstrap");

    @ParameterizedTest
    @MethodSource("eventTypes")
    void wireShapeIsBackwardCompatibleWithTheBaseline(String eventType) throws IOException {
        IntegrationEventEnvelope envelope = IntegrationEventFixtures.all().get(eventType);
        String currentJson = OBJECT_MAPPER.writeValueAsString(envelope);
        @SuppressWarnings("unchecked")
        Map<String, Object> current = OBJECT_MAPPER.readValue(currentJson, Map.class);

        Path baselinePath = EVENTS_DIR.resolve(eventType + ".json");
        if (Files.notExists(baselinePath)) {
            if (!BOOTSTRAP_MODE) {
                fail("Baseline file " + baselinePath + " is missing. If this is an intentional, reviewed "
                        + "breaking change or a genuinely new event type, regenerate it locally with "
                        + "-Dcontracts.baseline.bootstrap=true and commit the result -- a missing baseline "
                        + "must not silently pass.");
            }
            Files.createDirectories(EVENTS_DIR);
            Files.writeString(baselinePath, currentJson, StandardCharsets.UTF_8);
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> baseline = OBJECT_MAPPER.readValue(Files.readString(baselinePath), Map.class);
        List<String> violations = EventPayloadShapeChecker.compare(baseline, current);

        assertThat(violations)
                .as("Integration event backward-compatibility violations for " + eventType + " against the "
                        + "committed baseline; if intentional, update " + baselinePath
                        + " in this PR and bump IntegrationEventSchema.CURRENT_VERSION per ADR 0029")
                .isEmpty();
    }

    @Test
    void everySixKnownEventTypesHasAFixture() {
        assertThat(IntegrationEventFixtures.all()).hasSize(6);
    }

    static List<String> eventTypes() {
        return List.copyOf(IntegrationEventFixtures.all().keySet());
    }
}
