package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSecretsGuardTest {

    private static final String OVERRIDE_HMAC = "real-hmac-secret-from-vault";
    private static final String OVERRIDE_PSEUDONYM = "real-pseudonym-secret-from-vault";
    private static final String OVERRIDE_WEBHOOK_KEY = "q8lFeegq4dCZQxqe5z6HTOzFCiJI2f/iiB4gKn2ePGQ=";
    private static final String OVERRIDE_KEK = "D/d4CZZMPi+4f/3+7JoUrg0QEuVrXVNgQ8YTNigBcPk=";
    private static final String OVERRIDE_SUBJECT_ID = "real-subject-id-secret-from-vault";
    private static final String OVERRIDE_DEMO_RECEIVER = "real-demo-receiver-secret-from-vault";

    @Test
    void failsFastWhenProductionProfileKeepsAnyDefaultSecret() {
        MockEnvironment environment = productionProfile();

        ProductionSecretsGuard guard = new ProductionSecretsGuard(
                environment,
                "accountshield-local-only-challenge-secret",
                OVERRIDE_PSEUDONYM,
                OVERRIDE_WEBHOOK_KEY,
                OVERRIDE_KEK,
                OVERRIDE_SUBJECT_ID,
                OVERRIDE_DEMO_RECEIVER);

        assertThatThrownBy(guard::verifyNoDefaultSecretsInProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production")
                .hasMessageContaining("accountshield.challenge.hmac-secret");
    }

    @Test
    void failsFastListingEveryDefaultStillInUse() {
        MockEnvironment environment = productionProfile();

        ProductionSecretsGuard guard = new ProductionSecretsGuard(
                environment,
                "accountshield-local-only-challenge-secret",
                "accountshield-local-only-pseudonym-secret",
                OVERRIDE_WEBHOOK_KEY,
                OVERRIDE_KEK,
                OVERRIDE_SUBJECT_ID,
                OVERRIDE_DEMO_RECEIVER);

        assertThat(guard.collectDefaultsStillInUse())
                .containsExactlyInAnyOrder(
                        "accountshield.challenge.hmac-secret",
                        "accountshield.privacy.pseudonym-secret");
    }

    @Test
    void bootsWhenProductionProfileOverridesEverySecret() {
        MockEnvironment environment = productionProfile();

        ProductionSecretsGuard guard = allSecretsOverridden(environment);

        assertThatCode(guard::verifyNoDefaultSecretsInProduction).doesNotThrowAnyException();
        assertThat(guard.collectDefaultsStillInUse()).isEmpty();
    }

    @Test
    void allowsDefaultSecretsOutsideTheProductionProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        ProductionSecretsGuard guard = new ProductionSecretsGuard(
                environment,
                "accountshield-local-only-challenge-secret",
                "accountshield-local-only-pseudonym-secret",
                "MQK2zVpJuhFHt9iIhP2WkFZC0rW80SVg5vz9SStRMxQ=",
                "fV2x6TR85adre0B8wtaHGnLekX6MOoPjm1du9h/MBKY=",
                "accountshield-local-only-subject-id-secret",
                "accountshield-local-only-demo-receiver-secret");

        assertThatCode(guard::verifyNoDefaultSecretsInProduction).doesNotThrowAnyException();
    }

    @Test
    void catchesTheBase64DefaultKekAsWellAsHumanReadableDefaults() {
        MockEnvironment environment = productionProfile();

        ProductionSecretsGuard guard = new ProductionSecretsGuard(
                environment,
                OVERRIDE_HMAC,
                OVERRIDE_PSEUDONYM,
                OVERRIDE_WEBHOOK_KEY,
                "fV2x6TR85adre0B8wtaHGnLekX6MOoPjm1du9h/MBKY=",
                OVERRIDE_SUBJECT_ID,
                OVERRIDE_DEMO_RECEIVER);

        assertThat(guard.collectDefaultsStillInUse())
                .containsExactly("accountshield.crypto.active-kek-secret");
    }

    /**
     * Issue #144 / F-17: the demo webhook receiver's secret is checked independently of {@code
     * DemoWebhookReceiverController}'s own {@code @Profile("local")} gate, as a backstop for a
     * future controller that reintroduces the endpoint outside that profile.
     */
    @Test
    void catchesTheDefaultDemoReceiverSecret() {
        MockEnvironment environment = productionProfile();

        ProductionSecretsGuard guard = new ProductionSecretsGuard(
                environment,
                OVERRIDE_HMAC,
                OVERRIDE_PSEUDONYM,
                OVERRIDE_WEBHOOK_KEY,
                OVERRIDE_KEK,
                OVERRIDE_SUBJECT_ID,
                "accountshield-local-only-demo-receiver-secret");

        assertThat(guard.collectDefaultsStillInUse())
                .containsExactly("accountshield.webhook.demo-receiver.secret");
    }

    private static MockEnvironment productionProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(ProductionSecretsGuard.PRODUCTION_PROFILE);
        return environment;
    }

    private static ProductionSecretsGuard allSecretsOverridden(MockEnvironment environment) {
        return new ProductionSecretsGuard(
                environment,
                OVERRIDE_HMAC,
                OVERRIDE_PSEUDONYM,
                OVERRIDE_WEBHOOK_KEY,
                OVERRIDE_KEK,
                OVERRIDE_SUBJECT_ID,
                OVERRIDE_DEMO_RECEIVER);
    }
}
