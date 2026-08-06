package io.github.viniciusssantos.accountshield.webhook.internal;

import java.util.UUID;

class WebhookDeliveryException extends RuntimeException {

    WebhookDeliveryException(UUID subscriptionId, String deliveryId, Throwable cause) {
        super("webhook delivery to subscription " + subscriptionId + " (delivery " + deliveryId + ") failed: "
                + cause.getMessage(), cause);
    }
}
