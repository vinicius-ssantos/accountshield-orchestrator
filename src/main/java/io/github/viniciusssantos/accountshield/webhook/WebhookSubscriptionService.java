package io.github.viniciusssantos.accountshield.webhook;

import java.util.List;
import java.util.UUID;

public interface WebhookSubscriptionService {

    WebhookSecretIssued create(CreateWebhookSubscriptionCommand command, String actor);

    List<WebhookSubscriptionView> list();

    /** Throws {@link WebhookSubscriptionNotFoundException} if {@code subscriptionId} is unknown. */
    WebhookSecretIssued rotateSecret(UUID subscriptionId, String actor);

    /** Throws {@link WebhookSubscriptionNotFoundException} if {@code subscriptionId} is unknown. */
    void setEnabled(UUID subscriptionId, boolean enabled, String actor);
}
