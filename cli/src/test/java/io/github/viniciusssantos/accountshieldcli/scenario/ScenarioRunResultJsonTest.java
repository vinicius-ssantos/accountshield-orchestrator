package io.github.viniciusssantos.accountshieldcli.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshieldcli.JsonSupport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Proves a persisted run result round-trips exactly -- what 'scenario report' depends on. */
class ScenarioRunResultJsonTest {

    @Test
    void aRunResultRoundTripsThroughJsonUnchanged() {
        ScenarioRunResult original = new ScenarioRunResult(
                UUID.randomUUID(), "credential-stuffing", Instant.parse("2026-01-01T00:00:00Z"),
                "corr-1", UUID.randomUUID(), UUID.randomUUID(), "account-protection-default", "1.1.0",
                "risk-v1", 95, 95, "TEMPORARILY_BLOCK", "TEMPORARILY_BLOCK",
                List.of("COMPROMISED_CREDENTIAL", "FAILED_ATTEMPTS"), true, null);

        String json = JsonSupport.toPrettyJson(original);
        ScenarioRunResult roundTripped = JsonSupport.MAPPER.readValue(json, ScenarioRunResult.class);

        assertThat(roundTripped).isEqualTo(original);
    }
}
