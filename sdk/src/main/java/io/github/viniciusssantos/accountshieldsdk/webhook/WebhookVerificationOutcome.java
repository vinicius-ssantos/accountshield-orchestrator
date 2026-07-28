package io.github.viniciusssantos.accountshieldsdk.webhook;

public enum WebhookVerificationOutcome {
    ACCEPTED,
    STALE_TIMESTAMP,
    INVALID_SIGNATURE,
    DUPLICATE_DELIVERY
}
