package io.github.viniciusssantos.accountshield;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Refuses to boot under the {@code production} profile when any operator-managed secret is still at
 * its repository-published default value. This mirrors {@code challenge.SimulationModeGuard}'s
 * fail-fast posture: a deploy that forgets to override the KEK, HMAC, or pseudonym secret must fail
 * loudly rather than silently protecting production data with public key material.
 *
 * <p>The defaults checked here are the exact values baked into {@code application.yml} (and the
 * {@code @Value} fallbacks in {@code crypto.KeyEncryptionKeyResolver} and
 * {@code webhook.WebhookSecretCipher}). They are intentionally public so local development and the
 * demo compose stack work with zero configuration -- and intentionally rejected here so they can
 * never reach a production-like environment.
 */
@Component
class ProductionSecretsGuard {

    static final String PRODUCTION_PROFILE = "production";

    /**
     * The exact default values baked into {@code application.yml} and the {@code @Value} fallbacks.
     * If any configured secret equals one of these, it has not been overridden by the operator.
     */
    static final Set<String> KNOWN_DEFAULTS = Set.of(
            "accountshield-local-only-challenge-secret",
            "accountshield-local-only-pseudonym-secret",
            "accountshield-local-only-subject-id-secret",
            "accountshield-local-only-demo-receiver-secret",
            "fV2x6TR85adre0B8wtaHGnLekX6MOoPjm1du9h/MBKY=",
            "MQK2zVpJuhFHt9iIhP2WkFZC0rW80SVg5vz9SStRMxQ=");

    private final Environment environment;
    private final List<ConfiguredSecret> secrets;

    ProductionSecretsGuard(
            Environment environment,
            @Value("${accountshield.challenge.hmac-secret:accountshield-local-only-challenge-secret}")
            String challengeHmacSecret,
            @Value("${accountshield.privacy.pseudonym-secret:accountshield-local-only-pseudonym-secret}")
            String pseudonymSecret,
            @Value("${accountshield.webhook.secret-encryption-key:MQK2zVpJuhFHt9iIhP2WkFZC0rW80SVg5vz9SStRMxQ=}")
            String webhookSecretEncryptionKey,
            @Value("${accountshield.crypto.active-kek-secret:fV2x6TR85adre0B8wtaHGnLekX6MOoPjm1du9h/MBKY=}")
            String cryptoActiveKekSecret,
            @Value("${accountshield.crypto.subject-id-secret:accountshield-local-only-subject-id-secret}")
            String cryptoSubjectIdSecret,
            @Value("${accountshield.webhook.demo-receiver.secret:accountshield-local-only-demo-receiver-secret}")
            String webhookDemoReceiverSecret) {
        this.environment = environment;
        this.secrets = List.of(
                new ConfiguredSecret("accountshield.challenge.hmac-secret", challengeHmacSecret),
                new ConfiguredSecret("accountshield.privacy.pseudonym-secret", pseudonymSecret),
                new ConfiguredSecret("accountshield.webhook.secret-encryption-key", webhookSecretEncryptionKey),
                new ConfiguredSecret("accountshield.crypto.active-kek-secret", cryptoActiveKekSecret),
                new ConfiguredSecret("accountshield.crypto.subject-id-secret", cryptoSubjectIdSecret),
                // Checked independently of DemoWebhookReceiverController's own @Profile("local")
                // gate (issue #144 / F-17): this guard is the backstop for a future controller
                // that reintroduces the endpoint outside that profile.
                new ConfiguredSecret("accountshield.webhook.demo-receiver.secret", webhookDemoReceiverSecret));
    }

    @PostConstruct
    void verifyNoDefaultSecretsInProduction() {
        if (!environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILE))) {
            return;
        }
        List<String> stillDefault = collectDefaultsStillInUse();
        if (stillDefault.isEmpty()) {
            return;
        }
        throw new IllegalStateException(
                "The '" + PRODUCTION_PROFILE + "' profile is active but the following secrets are still at "
                        + "their repository-published default values, which are publicly known and must not "
                        + "protect production data: " + stillDefault
                        + ". Override each via its environment variable (see docs/RELEASING.md) and redeploy.");
    }

    List<String> collectDefaultsStillInUse() {
        List<String> stillDefault = new ArrayList<>();
        for (ConfiguredSecret secret : secrets) {
            if (secret.isAtDefault()) {
                stillDefault.add(secret.property());
            }
        }
        return List.copyOf(stillDefault);
    }

    private record ConfiguredSecret(String property, String value) {
        boolean isAtDefault() {
            return KNOWN_DEFAULTS.contains(value);
        }
    }
}
