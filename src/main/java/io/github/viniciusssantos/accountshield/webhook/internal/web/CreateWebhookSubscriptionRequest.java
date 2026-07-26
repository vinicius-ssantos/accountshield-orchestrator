package io.github.viniciusssantos.accountshield.webhook.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWebhookSubscriptionRequest(
        @NotBlank @Size(max = 2048) String url,
        @Size(max = 160) String eventTypeFilter) {
}
