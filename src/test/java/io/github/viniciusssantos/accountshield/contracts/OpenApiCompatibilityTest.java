package io.github.viniciusssantos.accountshield.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.viniciusssantos.accountshield.LocalJwtKeys;
import io.github.viniciusssantos.accountshield.PostgreSqlTestConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Compares the live {@code /v3/api-docs} document against the checked-in baseline
 * ({@code src/test/resources/contracts/openapi-baseline.json}) using
 * {@link OpenApiSchemaCompatibilityChecker}. Also always writes the current spec to
 * {@code target/contracts/openapi.json} as a build artifact (uploaded by CI, ADR 0029).
 *
 * <p>The baseline is pinned to the {@code v1.0.0} tagged contract. Any future incompatible
 * change fails this test unless the baseline file is deliberately updated in the same PR (a
 * visible, reviewable diff) and the API's major version is bumped per ADR 0029's versioning
 * policy. To regenerate the baseline after an intentional, reviewed breaking change, delete the
 * file and re-run this test (it writes the current spec in bootstrap mode), then commit the
 * result.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgreSqlTestConfiguration.class)
class OpenApiCompatibilityTest {

    private static final Path BASELINE_PATH = Path.of("src/test/resources/contracts/openapi-baseline.json");
    private static final Path ARTIFACT_PATH = Path.of("target/contracts/openapi.json");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocalJwtKeys localJwtKeys;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @SuppressWarnings("unchecked")
    void currentApiDocsAreBackwardCompatibleWithTheBaseline() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andReturn();
        String currentJson = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> current = objectMapper.readValue(currentJson, Map.class);

        writeArtifact(currentJson);

        if (Files.notExists(BASELINE_PATH)) {
            Files.createDirectories(BASELINE_PATH.getParent());
            Files.writeString(BASELINE_PATH, currentJson, StandardCharsets.UTF_8);
            return;
        }

        Map<String, Object> baseline = objectMapper.readValue(Files.readString(BASELINE_PATH), Map.class);
        List<String> violations = OpenApiSchemaCompatibilityChecker.compare(baseline, current);

        assertThat(violations)
                .as("OpenAPI backward-compatibility violations against the committed baseline; "
                        + "if intentional, update src/test/resources/contracts/openapi-baseline.json "
                        + "in this PR and bump the API's major version per ADR 0029")
                .isEmpty();
    }

    private String bearer() {
        return "Bearer " + localJwtKeys.signToken("contract-test", List.of("SECURITY_OPERATOR"),
                Duration.ofMinutes(5), Clock.systemUTC());
    }

    private void writeArtifact(String currentJson) throws IOException {
        Files.createDirectories(ARTIFACT_PATH.getParent());
        Files.writeString(ARTIFACT_PATH, currentJson, StandardCharsets.UTF_8);
    }
}
