package io.github.viniciusssantos.accountshield.webhook.internal;

import io.github.viniciusssantos.accountshield.outbox.IntegrationEventSchema;
import io.github.viniciusssantos.accountshield.outbox.OutboxEventPublisher;
import io.github.viniciusssantos.accountshield.outbox.OutboxMessage;
import io.github.viniciusssantos.accountshield.webhook.internal.persistence.WebhookSubscriptionEntity;
import io.github.viniciusssantos.accountshield.webhook.internal.persistence.WebhookSubscriptionRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

/**
 * Real {@link OutboxEventPublisher} implementation: signs and delivers each outbox message to
 * every ACTIVE subscription matching its event type. Registered automatically in place of the
 * outbox module's log-only default ({@code OutboxConfiguration} only registers that default via
 * {@code @ConditionalOnMissingBean}).
 *
 * <p>All existing outbox retry/backoff/dead-letter/delivery-ID machinery (ADR 0023) is reused
 * unchanged: {@link #publish} throwing on any failed delivery is exactly what makes {@code
 * OutboxRelay} retry the whole event, and {@code OutboxMessage#id()} -- unchanged across retries
 * -- is already the delivery ID this issue's acceptance criteria ask for.
 */
@Component
public class WebhookEventPublisher implements OutboxEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventPublisher.class);
    private static final String SCHEMA_VERSION_FIELD = "schemaVersion";

    private final WebhookSubscriptionRepository repository;
    private final WebhookSecretCipher secretCipher;
    private final WebhookSigner signer;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RestClient restClient;

    public WebhookEventPublisher(
            WebhookSubscriptionRepository repository,
            WebhookSecretCipher secretCipher,
            WebhookSigner signer,
            ObjectMapper objectMapper,
            @Qualifier("decisionClock") Clock clock,
            @Value("${accountshield.webhook.delivery.timeout:5s}") Duration timeout) {
        this.repository = repository;
        this.secretCipher = secretCipher;
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.clock = clock;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) timeout.toMillis());
        requestFactory.setReadTimeout((int) timeout.toMillis());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public void publish(OutboxMessage message) {
        List<WebhookSubscriptionEntity> subscriptions = repository.findByStatus("ACTIVE").stream()
                .filter(subscription -> subscription.matchesEventType(message.eventType()))
                .toList();
        if (subscriptions.isEmpty()) {
            return;
        }
        String timestamp = String.valueOf(clock.instant().getEpochSecond());
        String deliveryId = message.id().toString();
        String rawBody = message.payload();
        String schemaVersion = extractSchemaVersion(rawBody);
        for (WebhookSubscriptionEntity subscription : subscriptions) {
            deliver(subscription, timestamp, deliveryId, rawBody, schemaVersion);
        }
    }

    private void deliver(
            WebhookSubscriptionEntity subscription, String timestamp, String deliveryId, String rawBody,
            String schemaVersion) {
        String secret = secretCipher.decrypt(subscription.getSecretCiphertext(), subscription.getSecretNonce());
        String signature = signer.sign(secret, timestamp, deliveryId, rawBody);
        try {
            restClient.post()
                    .uri(subscription.getUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Webhook-Signature", signature)
                    .header("X-Webhook-Timestamp", timestamp)
                    .header("X-Webhook-Delivery-Id", deliveryId)
                    .header("X-Webhook-Schema-Version", schemaVersion)
                    .body(rawBody)
                    .retrieve()
                    .toBodilessEntity();
            log.info("webhook_delivered subscription_id={} delivery_id={}", subscription.getId(), deliveryId);
        } catch (RestClientException exception) {
            throw new WebhookDeliveryException(subscription.getId(), deliveryId, exception);
        }
    }

    private String extractSchemaVersion(String rawBody) {
        try {
            Map<?, ?> envelope = objectMapper.readValue(rawBody, Map.class);
            Object version = envelope.get(SCHEMA_VERSION_FIELD);
            return version == null ? IntegrationEventSchema.CURRENT_VERSION : version.toString();
        } catch (RuntimeException exception) {
            return IntegrationEventSchema.CURRENT_VERSION;
        }
    }
}
