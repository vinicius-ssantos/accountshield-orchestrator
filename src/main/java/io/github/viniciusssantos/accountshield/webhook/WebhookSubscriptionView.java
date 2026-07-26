package io.github.viniciusssantos.accountshield.webhook;

import java.time.Instant;
import java.util.UUID;

/** Never carries the subscription secret -- only {@link WebhookSecretIssued} does, once. */
public record WebhookSubscriptionView(
        UUID id,
        String url,
        String eventTypeFilter,
        WebhookSubscriptionStatus status,
        Instant createdAt,
        Instant secretRotatedAt) {
}
