package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.webhook.CreateWebhookSubscriptionCommand;
import io.github.viniciusssantos.accountshield.webhook.WebhookSecretIssued;
import io.github.viniciusssantos.accountshield.webhook.WebhookSubscriptionNotFoundException;
import io.github.viniciusssantos.accountshield.webhook.WebhookSubscriptionService;
import io.github.viniciusssantos.accountshield.webhook.WebhookSubscriptionStatus;
import io.github.viniciusssantos.accountshield.webhook.WebhookSubscriptionView;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class WebhookSubscriptionServiceIntegrationTest {

    @Autowired
    private WebhookSubscriptionService webhookSubscriptionService;

    @Test
    void createReturnsTheSecretOnceAndListNeverExposesIt() {
        WebhookSecretIssued issued = webhookSubscriptionService.create(
                new CreateWebhookSubscriptionCommand("https://example.test/receiver-" + UUID.randomUUID(), null),
                "operator-1");

        assertThat(issued.secret()).isNotBlank();

        WebhookSubscriptionView view = webhookSubscriptionService.list().stream()
                .filter(subscription -> subscription.id().equals(issued.subscriptionId()))
                .findFirst()
                .orElseThrow();
        assertThat(view.status()).isEqualTo(WebhookSubscriptionStatus.ACTIVE);
        assertThat(view.secretRotatedAt()).isNull();
    }

    @Test
    void rotateSecretIssuesADifferentSecretEachTime() {
        WebhookSecretIssued created = webhookSubscriptionService.create(
                new CreateWebhookSubscriptionCommand("https://example.test/receiver-" + UUID.randomUUID(), null),
                "operator-1");

        WebhookSecretIssued rotated = webhookSubscriptionService.rotateSecret(
                created.subscriptionId(), "operator-1");

        assertThat(rotated.secret()).isNotEqualTo(created.secret());
        WebhookSubscriptionView view = webhookSubscriptionService.list().stream()
                .filter(subscription -> subscription.id().equals(created.subscriptionId()))
                .findFirst()
                .orElseThrow();
        assertThat(view.secretRotatedAt()).isNotNull();
    }

    @Test
    void setEnabledTogglesSubscriptionStatus() {
        WebhookSecretIssued created = webhookSubscriptionService.create(
                new CreateWebhookSubscriptionCommand("https://example.test/receiver-" + UUID.randomUUID(), null),
                "operator-1");

        webhookSubscriptionService.setEnabled(created.subscriptionId(), false, "operator-1");

        WebhookSubscriptionView disabled = webhookSubscriptionService.list().stream()
                .filter(subscription -> subscription.id().equals(created.subscriptionId()))
                .findFirst()
                .orElseThrow();
        assertThat(disabled.status()).isEqualTo(WebhookSubscriptionStatus.DISABLED);

        webhookSubscriptionService.setEnabled(created.subscriptionId(), true, "operator-1");

        WebhookSubscriptionView reEnabled = webhookSubscriptionService.list().stream()
                .filter(subscription -> subscription.id().equals(created.subscriptionId()))
                .findFirst()
                .orElseThrow();
        assertThat(reEnabled.status()).isEqualTo(WebhookSubscriptionStatus.ACTIVE);
    }

    @Test
    void operationsOnAnUnknownSubscriptionThrowNotFound() {
        UUID unknownId = UUID.randomUUID();

        assertThatThrownBy(() -> webhookSubscriptionService.rotateSecret(unknownId, "operator-1"))
                .isInstanceOf(WebhookSubscriptionNotFoundException.class);
        assertThatThrownBy(() -> webhookSubscriptionService.setEnabled(unknownId, false, "operator-1"))
                .isInstanceOf(WebhookSubscriptionNotFoundException.class);
    }
}
