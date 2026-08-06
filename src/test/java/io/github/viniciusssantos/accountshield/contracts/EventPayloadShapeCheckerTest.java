package io.github.viniciusssantos.accountshield.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventPayloadShapeCheckerTest {

    @Test
    void identicalFixturesHaveNoViolations() {
        Map<String, Object> fixture = sampleFixture();

        assertThat(EventPayloadShapeChecker.compare(fixture, new LinkedHashMap<>(fixture))).isEmpty();
    }

    @Test
    void additiveFieldsAreAllowed() {
        Map<String, Object> baseline = sampleFixture();
        Map<String, Object> current = new LinkedHashMap<>(baseline);
        current.put("newField", "value");

        assertThat(EventPayloadShapeChecker.compare(baseline, current)).isEmpty();
    }

    @Test
    void detectsARemovedField() {
        Map<String, Object> baseline = sampleFixture();
        Map<String, Object> current = new LinkedHashMap<>(baseline);
        current.remove("outcome");

        List<String> violations = EventPayloadShapeChecker.compare(baseline, current);

        assertThat(violations).anyMatch(v -> v.contains("field removed") && v.contains("outcome"));
    }

    @Test
    void detectsATypeChange() {
        Map<String, Object> baseline = sampleFixture();
        Map<String, Object> current = new LinkedHashMap<>(baseline);
        current.put("riskScore", "10");

        List<String> violations = EventPayloadShapeChecker.compare(baseline, current);

        assertThat(violations).anyMatch(v -> v.contains("type changed") && v.contains("riskScore"));
    }

    @Test
    void detectsARemovedNestedField() {
        Map<String, Object> baseline = sampleFixture();
        Map<String, Object> current = new LinkedHashMap<>(baseline);
        Map<String, Object> envelope = new LinkedHashMap<>((Map<String, Object>) baseline.get("envelope"));
        envelope.remove("schemaVersion");
        current.put("envelope", envelope);

        List<String> violations = EventPayloadShapeChecker.compare(baseline, current);

        assertThat(violations).anyMatch(v -> v.contains("field removed") && v.contains("schemaVersion"));
    }

    @Test
    void nullBaselineFieldIsSkipped() {
        Map<String, Object> baseline = sampleFixture();
        baseline.put("degradationReason", null);
        Map<String, Object> current = new LinkedHashMap<>(baseline);

        assertThat(EventPayloadShapeChecker.compare(baseline, current)).isEmpty();
    }

    private Map<String, Object> sampleFixture() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", "integration-event-1.0");
        envelope.put("eventId", "11111111-1111-1111-1111-111111111111");

        Map<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("outcome", "ALLOW");
        fixture.put("riskScore", 10);
        fixture.put("degraded", false);
        fixture.put("envelope", envelope);
        return fixture;
    }
}
