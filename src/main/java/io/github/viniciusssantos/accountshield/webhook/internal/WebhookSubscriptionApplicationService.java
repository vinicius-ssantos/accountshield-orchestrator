package io.github.viniciusssantos.accountshield.webhook.internal;

import io.github.viniciusssantos.accountshield.webhook.CreateWebhookSubscriptionCommand;
import io.github.viniciusssantos.accountshield.webhook.WebhookSecretIssued;
import io.github.viniciusssantos.accountshield.webhook.WebhookSubscriptionNotFoundException;
import io.github.viniciusssantos.accountshield.webhook.WebhookSubscriptionService;
import io.github.viniciusssantos.accountshield.webhook.WebhookSubscriptionStatus;
import io.github.viniciusssantos.accountshield.webhook.WebhookSubscriptionView;
import io.github.viniciusssantos.accountshield.webhook.internal.persistence.WebhookSubscriptionEntity;
import io.github.viniciusssantos.accountshield.webhook.internal.persistence.WebhookSubscriptionRepository;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WebhookSubscriptionApplicationService implements WebhookSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(WebhookSubscriptionApplicationService.class);

    private final WebhookSubscriptionRepository repository;
    private final WebhookSecretCipher secretCipher;
    private final WebhookSecretGenerator secretGenerator;
    private final Clock clock;

    WebhookSubscriptionApplicationService(
            WebhookSubscriptionRepository repository,
            WebhookSecretCipher secretCipher,
            WebhookSecretGenerator secretGenerator,
            @Qualifier("decisionClock") Clock clock) {
        this.repository = repository;
        this.secretCipher = secretCipher;
        this.secretGenerator = secretGenerator;
        this.clock = clock;
    }

    @Override
    @Transactional
    public WebhookSecretIssued create(CreateWebhookSubscriptionCommand command, String actor) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(actor, "actor must not be null");

        String secret = secretGenerator.generate();
        WebhookSecretCipher.EncryptedSecret encrypted = secretCipher.encrypt(secret);
        UUID id = UUID.randomUUID();
        WebhookSubscriptionEntity entity = new WebhookSubscriptionEntity(
                id, command.url(), command.eventTypeFilter(),
                encrypted.ciphertext(), encrypted.nonce(), clock.instant());
        repository.save(entity);
        log.info("webhook_subscription_created subscription_id={} actor={}", id, actor);
        return new WebhookSecretIssued(id, secret);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WebhookSubscriptionView> list() {
        return repository.findAll().stream().map(WebhookSubscriptionApplicationService::toView).toList();
    }

    @Override
    @Transactional
    public WebhookSecretIssued rotateSecret(UUID subscriptionId, String actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        WebhookSubscriptionEntity entity = loadOrThrow(subscriptionId);

        String secret = secretGenerator.generate();
        WebhookSecretCipher.EncryptedSecret encrypted = secretCipher.encrypt(secret);
        entity.rotateSecret(encrypted.ciphertext(), encrypted.nonce(), clock.instant());
        log.info("webhook_subscription_secret_rotated subscription_id={} actor={}", subscriptionId, actor);
        return new WebhookSecretIssued(subscriptionId, secret);
    }

    @Override
    @Transactional
    public void setEnabled(UUID subscriptionId, boolean enabled, String actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        WebhookSubscriptionEntity entity = loadOrThrow(subscriptionId);

        entity.setEnabled(enabled);
        log.info("webhook_subscription_{} subscription_id={} actor={}",
                enabled ? "enabled" : "disabled", subscriptionId, actor);
    }

    private WebhookSubscriptionEntity loadOrThrow(UUID subscriptionId) {
        Objects.requireNonNull(subscriptionId, "subscriptionId must not be null");
        return repository.findById(subscriptionId)
                .orElseThrow(() -> new WebhookSubscriptionNotFoundException(subscriptionId));
    }

    private static WebhookSubscriptionView toView(WebhookSubscriptionEntity entity) {
        return new WebhookSubscriptionView(
                entity.getId(),
                entity.getUrl(),
                entity.getEventTypeFilter(),
                WebhookSubscriptionStatus.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getSecretRotatedAt());
    }
}
