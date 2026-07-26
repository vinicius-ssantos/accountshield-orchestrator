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

/**
 * Exercises the real {@link WebhookEventPublisher} against the real demo receiver over an actual
 * HTTP connection on this app's own random test port -- proving the signing/delivery contract
 * end to end, not just at the unit level.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgreSqlTestConfiguration.class)
class WebhookDeliveryIntegrationTest {

    @Autowired
    private OutboxEventPublisher publisher;

    @Autowired
    private WebhookSubscriptionRepository repository;

    @Autowired
    private WebhookSecretCipher secretCipher;

    @Value("${accountshield.webhook.demo-receiver.secret}")
    private String demoReceiverSecret;

    @Value("${local.server.port}")
    private int port;

    @Test
    void publishIsANoOpWhenNoSubscriptionsMatch() {
        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID(), "Test", "agg-1", "TEST_EVENT", "{\"a\":1}", Instant.now());

        assertThatCode(() -> publisher.publish(message)).doesNotThrowAnyException();
    }

    @Test
    void deliversToAMatchingActiveSubscriptionAndTheReceiverAccepts() {
        insertSubscription(demoReceiverSecret, null, true);
        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID(), "Test", "agg-2", "TEST_EVENT",
                "{\"schemaVersion\":\"integration-event-1.0\"}", Instant.now());

        assertThatCode(() -> publisher.publish(message)).doesNotThrowAnyException();
    }

    @Test
    void redeliveringTheSameEventIsRejectedByTheReceiverAsADuplicate() {
        insertSubscription(demoReceiverSecret, null, true);
        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID(), "Test", "agg-3", "TEST_EVENT",
                "{\"schemaVersion\":\"integration-event-1.0\"}", Instant.now());

        publisher.publish(message);

        assertThatThrownBy(() -> publisher.publish(message)).isInstanceOf(WebhookDeliveryException.class);
    }

    @Test
    void disabledSubscriptionsAreSkipped() {
        insertSubscription(demoReceiverSecret, null, false);
        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID(), "Test", "agg-4", "TEST_EVENT", "{}", Instant.now());

        assertThatCode(() -> publisher.publish(message)).doesNotThrowAnyException();
    }

    @Test
    void mismatchedEventTypeFilterIsSkipped() {
        insertSubscription(demoReceiverSecret, "SOME_OTHER_EVENT", true);
        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID(), "Test", "agg-5", "TEST_EVENT", "{}", Instant.now());

        assertThatCode(() -> publisher.publish(message)).doesNotThrowAnyException();
    }

    @Test
    void wrongSubscriptionSecretFailsSignatureVerificationAtTheReceiver() {
        insertSubscription("a-completely-wrong-secret", null, true);
        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID(), "Test", "agg-6", "TEST_EVENT", "{}", Instant.now());

        assertThatThrownBy(() -> publisher.publish(message)).isInstanceOf(WebhookDeliveryException.class);
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
