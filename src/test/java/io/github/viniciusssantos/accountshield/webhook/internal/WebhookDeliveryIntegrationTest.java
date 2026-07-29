package io.github.viniciusssantos.accountshield.webhook.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.PostgreSqlTestConfiguration;
import io.github.viniciusssantos.accountshield.outbox.OutboxEventPublisher;
import io.github.viniciusssantos.accountshield.outbox.OutboxMessage;
import io.github.viniciusssantos.accountshield.webhook.internal.persistence.WebhookSubscriptionEntity;
import io.github.viniciusssantos.accountshield.webhook.internal.persistence.WebhookSubscriptionRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Exercises the real {@link WebhookEventPublisher} against the real demo receiver over an actual
 * HTTP connection on this app's own random test port -- proving the signing/delivery contract
 * end to end, not just at the unit level.
 *
 * <p>Subscription rows persist for the lifetime of this (non-{@code @Transactional}) test class,
 * so every test uses its own unique event type, with subscriptions filtered to match only that
 * type -- otherwise an earlier test's subscription (especially a null/match-everything filter,
 * or one deliberately configured with a wrong secret) would leak into a later test's delivery
 * attempt and fail it for the wrong reason.
 *
 * <p>{@code @ActiveProfiles("local")}: {@code DemoWebhookReceiverController} is now
 * {@code @Profile("local")}-gated (issue #144 / F-17), so this test activates that profile to
 * keep exercising the real endpoint rather than a mock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgreSqlTestConfiguration.class)
@ActiveProfiles("local")
class WebhookDeliveryIntegrationTest {

    @Autowired
    private OutboxEventPublisher publisher;

    @Autowired
    private WebhookSubscriptionRepository repository;

    @Autowired
    private WebhookSecretCipher secretCipher;

    @Value("${accountshield.webhook.demo-receiver.secret:accountshield-local-only-demo-receiver-secret}")
    private String demoReceiverSecret;

    @Value("${local.server.port}")
    private int port;

    @Test
    void publishIsANoOpWhenNoSubscriptionsMatch() {
        String eventType = uniqueEventType();
        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID(), "Test", "agg-1", eventType, "{\"a\":1}", Instant.now());

        assertThatCode(() -> publisher.publish(message)).doesNotThrowAnyException();
    }

    @Test
    void deliversToAMatchingActiveSubscriptionAndTheReceiverAccepts() {
        String eventType = uniqueEventType();
        insertSubscription(demoReceiverSecret, eventType, true);
        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID(), "Test", "agg-2", eventType,
                "{\"schemaVersion\":\"integration-event-1.0\"}", Instant.now());

        assertThatCode(() -> publisher.publish(message)).doesNotThrowAnyException();
    }

    @Test
    void redeliveringTheSameEventIsRejectedByTheReceiverAsADuplicate() {
        String eventType = uniqueEventType();
        insertSubscription(demoReceiverSecret, eventType, true);
        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID(), "Test", "agg-3", eventType,
                "{\"schemaVersion\":\"integration-event-1.0\"}", Instant.now());

        publisher.publish(message);

        assertThatThrownBy(() -> publisher.publish(message)).isInstanceOf(WebhookDeliveryException.class);
    }

    @Test
    void disabledSubscriptionsAreSkipped() {
        String eventType = uniqueEventType();
        insertSubscription(demoReceiverSecret, eventType, false);
        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID(), "Test", "agg-4", eventType, "{}", Instant.now());

        assertThatCode(() -> publisher.publish(message)).doesNotThrowAnyException();
    }

    @Test
    void mismatchedEventTypeFilterIsSkipped() {
        String eventType = uniqueEventType();
        insertSubscription(demoReceiverSecret, eventType + "_OTHER", true);
        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID(), "Test", "agg-5", eventType, "{}", Instant.now());

        assertThatCode(() -> publisher.publish(message)).doesNotThrowAnyException();
    }

    @Test
    void wrongSubscriptionSecretFailsSignatureVerificationAtTheReceiver() {
        String eventType = uniqueEventType();
        insertSubscription("a-completely-wrong-secret", eventType, true);
        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID(), "Test", "agg-6", eventType, "{}", Instant.now());

        assertThatThrownBy(() -> publisher.publish(message)).isInstanceOf(WebhookDeliveryException.class);
    }

    private static String uniqueEventType() {
        return "TEST_EVENT_" + UUID.randomUUID();
    }

    private void insertSubscription(String plaintextSecret, String eventTypeFilter, boolean enabled) {
        WebhookSecretCipher.EncryptedSecret encrypted = secretCipher.encrypt(plaintextSecret);
        WebhookSubscriptionEntity entity = new WebhookSubscriptionEntity(
                UUID.randomUUID(),
                "http://localhost:" + port + "/demo/webhook-receiver",
                eventTypeFilter,
                encrypted.ciphertext(),
                encrypted.nonce(),
                Instant.now());
        if (!enabled) {
            entity.setEnabled(false);
        }
        repository.save(entity);
    }
}
