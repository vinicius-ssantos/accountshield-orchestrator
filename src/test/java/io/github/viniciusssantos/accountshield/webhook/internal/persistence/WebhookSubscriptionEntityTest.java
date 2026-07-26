package io.github.viniciusssantos.accountshield.webhook.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookSubscriptionEntityTest {

    @Test
    void nullEventTypeFilterMatchesAnyEventType() {
        WebhookSubscriptionEntity entity = new WebhookSubscriptionEntity(
                UUID.randomUUID(), "https://example.test", null, new byte[]{1}, new byte[]{2}, Instant.now());

        assertThat(entity.matchesEventType("ANY_EVENT")).isTrue();
        assertThat(entity.matchesEventType("A_DIFFERENT_EVENT")).isTrue();
    }

    @Test
    void nonNullEventTypeFilterMatchesOnlyThatType() {
        WebhookSubscriptionEntity entity = new WebhookSubscriptionEntity(
                UUID.randomUUID(), "https://example.test", "SPECIFIC_EVENT",
                new byte[]{1}, new byte[]{2}, Instant.now());

        assertThat(entity.matchesEventType("SPECIFIC_EVENT")).isTrue();
        assertThat(entity.matchesEventType("OTHER_EVENT")).isFalse();
    }
}
