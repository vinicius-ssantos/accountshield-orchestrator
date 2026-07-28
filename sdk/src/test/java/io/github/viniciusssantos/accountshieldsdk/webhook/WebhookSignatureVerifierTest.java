package io.github.viniciusssantos.accountshieldsdk.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "test-webhook-secret";

    @Test
    void acceptsAValidSignatureOnFirstDelivery() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(SECRET, Duration.ofMinutes(5), clock, 100);
        String timestamp = String.valueOf(clock.instant().getEpochSecond());
        String deliveryId = UUID.randomUUID().toString();
        String body = "{\"eventType\":\"protection.decision.made\"}";
        String signature = new WebhookSigner().sign(SECRET, timestamp, deliveryId, body);

        WebhookVerificationResult result = verifier.verify(signature, timestamp, deliveryId, body);

        assertThat(result.accepted()).isTrue();
        assertThat(result.outcome()).isEqualTo(WebhookVerificationOutcome.ACCEPTED);
    }

    @Test
    void rejectsAStaleTimestamp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(SECRET, Duration.ofMinutes(5), clock, 100);
        String staleTimestamp = String.valueOf(clock.instant().minus(Duration.ofHours(1)).getEpochSecond());
        String deliveryId = UUID.randomUUID().toString();
        String body = "{}";
        String signature = new WebhookSigner().sign(SECRET, staleTimestamp, deliveryId, body);

        WebhookVerificationResult result = verifier.verify(signature, staleTimestamp, deliveryId, body);

        assertThat(result.outcome()).isEqualTo(WebhookVerificationOutcome.STALE_TIMESTAMP);
    }

    @Test
    void rejectsATamperedSignature() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(SECRET, Duration.ofMinutes(5), clock, 100);
        String timestamp = String.valueOf(clock.instant().getEpochSecond());
        String deliveryId = UUID.randomUUID().toString();
        String signature = new WebhookSigner().sign(SECRET, timestamp, deliveryId, "{\"original\":true}");

        WebhookVerificationResult result = verifier.verify(signature, timestamp, deliveryId, "{\"tampered\":true}");

        assertThat(result.outcome()).isEqualTo(WebhookVerificationOutcome.INVALID_SIGNATURE);
    }

    @Test
    void rejectsAReplayedDeliveryId() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(SECRET, Duration.ofMinutes(5), clock, 100);
        String timestamp = String.valueOf(clock.instant().getEpochSecond());
        String deliveryId = UUID.randomUUID().toString();
        String body = "{}";
        String signature = new WebhookSigner().sign(SECRET, timestamp, deliveryId, body);

        WebhookVerificationResult first = verifier.verify(signature, timestamp, deliveryId, body);
        WebhookVerificationResult replay = verifier.verify(signature, timestamp, deliveryId, body);

        assertThat(first.accepted()).isTrue();
        assertThat(replay.outcome()).isEqualTo(WebhookVerificationOutcome.DUPLICATE_DELIVERY);
    }
}
