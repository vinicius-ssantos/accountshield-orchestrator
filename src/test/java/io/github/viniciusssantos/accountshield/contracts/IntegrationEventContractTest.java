package io.github.viniciusssantos.accountshield.contracts;

import static org.assertj.core.api.Assertions.assertThat;

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
 * {@link EventPayloadShapeChecker}. Self-bootstraps the same way as
 * {@link OpenApiCompatibilityTest}: this repository has no tagged release yet, so on the first run
 * for a given event type this test captures the current shape as the baseline and passes. See
 * ADR 0029.
 */
class IntegrationEventContractTest {

    private static final Path EVENTS_DIR = Path.of("src/test/resources/contracts/events");
    private static final ObjectMapper OBJECT_MAPPER = IntegrationEventFixtures.objectMapper();

    @ParameterizedTest
    @MethodSource("eventTypes")
    void wireShapeIsBackwardCompatibleWithTheBaseline(String eventType) throws IOException {
        IntegrationEventEnvelope envelope = IntegrationEventFixtures.all().get(eventType);
        String currentJson = OBJECT_MAPPER.writeValueAsString(envelope);
        @SuppressWarnings("unchecked")
        Map<String, Object> current = OBJECT_MAPPER.readValue(currentJson, Map.class);

        Path baselinePath = EVENTS_DIR.resolve(eventType + ".json");
        if (Files.notExists(baselinePath)) {
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
