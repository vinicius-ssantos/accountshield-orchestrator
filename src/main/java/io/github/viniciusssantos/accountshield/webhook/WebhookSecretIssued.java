package io.github.viniciusssantos.accountshield.webhook;

import java.util.UUID;

/**
 * Returned exactly once, at subscription creation and at each secret rotation. No other API
 * response, listing, or log line ever carries the plaintext secret again.
 */
public record WebhookSecretIssued(UUID subscriptionId, String secret) {
}
