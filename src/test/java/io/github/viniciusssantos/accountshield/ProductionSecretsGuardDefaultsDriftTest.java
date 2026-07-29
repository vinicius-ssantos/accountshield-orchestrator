package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Issue #153 / F-26: {@link ProductionSecretsGuardTest} constructs {@link ProductionSecretsGuard}
 * with literal default strings duplicated from {@code application.yml} -- nothing previously
 * verified those literals (and {@link ProductionSecretsGuard#KNOWN_DEFAULTS}, which the guard
 * checks against) still match the real defaults {@code application.yml} declares. If a default
 * value changed in the YAML without a matching update to {@code KNOWN_DEFAULTS}, the production
 * fail-fast guard would silently stop detecting it -- an open failure, the opposite of the
 * guard's purpose.
 *
 * <p>This reads {@code src/main/resources/application.yml} directly rather than resolving
 * properties through a {@code @SpringBootTest} context: {@code src/test/resources/application.yml}
 * occupies the same classpath location ({@code classpath:/application.yml}) and shadows the main
 * one entirely for Spring's property resolution in tests, so a Spring-context-based check would
 * never actually see the real file this test exists to verify.
 */
class ProductionSecretsGuardDefaultsDriftTest {

    private static final Path APPLICATION_YML = Path.of("src/main/resources/application.yml");
    private static final Pattern DEFAULT_VALUE = Pattern.compile("\\$\\{[A-Z0-9_]+:(.*)}");

    private static final List<String> SECRET_PROPERTY_PATHS = List.of(
            "accountshield.challenge.hmac-secret",
            "accountshield.privacy.pseudonym-secret",
            "accountshield.webhook.secret-encryption-key",
            "accountshield.webhook.demo-receiver.secret",
            "accountshield.crypto.active-kek-secret",
            "accountshield.crypto.subject-id-secret");

    @Test
    void everyApplicationYmlDefaultSecretIsKnownToTheProductionGuard() throws IOException {
        Map<String, Object> yaml = loadYaml();

        for (String propertyPath : SECRET_PROPERTY_PATHS) {
            String rawValue = (String) navigate(yaml, propertyPath);
            String defaultValue = extractDefault(propertyPath, rawValue);

            assertThat(ProductionSecretsGuard.KNOWN_DEFAULTS)
                    .as("application.yml's %s default ('%s') must be covered by "
                            + "ProductionSecretsGuard.KNOWN_DEFAULTS, or the production fail-fast guard "
                            + "silently stops detecting it", propertyPath, defaultValue)
                    .contains(defaultValue);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml() throws IOException {
        try (FileInputStream input = new FileInputStream(APPLICATION_YML.toFile())) {
            return (Map<String, Object>) new Yaml().load(input);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object navigate(Map<String, Object> yaml, String dotPath) {
        Object current = yaml;
        for (String segment : dotPath.split("\\.")) {
            assertThat(current)
                    .as("expected a YAML mapping while navigating to '%s' at segment '%s'", dotPath, segment)
                    .isInstanceOf(Map.class);
            current = ((Map<String, Object>) current).get(segment);
            assertThat(current).as("missing YAML key '%s' (full path '%s')", segment, dotPath).isNotNull();
        }
        return current;
    }

    private static String extractDefault(String propertyPath, String rawValue) {
        Matcher matcher = DEFAULT_VALUE.matcher(rawValue);
        assertThat(matcher.matches())
                .as("expected '%s' (property '%s') to match ${ENV_VAR:default}", rawValue, propertyPath)
                .isTrue();
        return matcher.group(1);
    }
}
