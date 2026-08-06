package io.github.viniciusssantos.accountshield.webhook;

/** {@code eventTypeFilter} null subscribes to every outbox event type. */
public record CreateWebhookSubscriptionCommand(String url, String eventTypeFilter) {
}
