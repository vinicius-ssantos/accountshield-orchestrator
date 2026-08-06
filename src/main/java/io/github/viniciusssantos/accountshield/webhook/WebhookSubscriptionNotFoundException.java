package io.github.viniciusssantos.accountshield.webhook;

import java.util.UUID;

public class WebhookSubscriptionNotFoundException extends RuntimeException {

    private final UUID subscriptionId;

    public WebhookSubscriptionNotFoundException(UUID subscriptionId) {
        super("webhook subscription " + subscriptionId + " was not found");
        this.subscriptionId = subscriptionId;
    }

    public UUID subscriptionId() {
        return subscriptionId;
    }
}
