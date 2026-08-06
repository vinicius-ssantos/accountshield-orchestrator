package io.github.viniciusssantos.accountshieldsdk.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies an inbound AccountShield webhook exactly the way the server's own reference receiver
 * does (see {@code webhook.internal.demo.DemoWebhookReceiverController} -- this is a faithful,
 * independently-implemented port of that exact logic and header names, not a simplification):
 * HMAC-SHA256 over {@code timestamp + "." + deliveryId + "." + rawBody} using the shared secret's
 * raw UTF-8 bytes as the key, hex-encoded (lowercase); checked in order (1) timestamp freshness,
 * (2) constant-time signature comparison, (3) delivery-ID replay dedup -- so a caller can
 * distinguish "stale" from "forged" from "replayed" for its own logging/metrics.
 */
public final class WebhookSignatureVerifier {

    public static final String SIGNATURE_HEADER = "X-Webhook-Signature";
    public static final String TIMESTAMP_HEADER = "X-Webhook-Timestamp";
    public static final String DELIVERY_ID_HEADER = "X-Webhook-Delivery-Id";

    private static final String ALGORITHM = "HmacSHA256";
    private static final int DEFAULT_MAX_SEEN_DELIVERIES = 10_000;

    private final SecretKeySpec key;
    private final Duration maxTimestampSkew;
    private final Clock clock;
    private final Map<String, Instant> seenDeliveryIds;

    public WebhookSignatureVerifier(String secret) {
        this(secret, Duration.ofMinutes(5), Clock.systemUTC(), DEFAULT_MAX_SEEN_DELIVERIES);
    }

    public WebhookSignatureVerifier(String secret, Duration maxTimestampSkew, Clock clock, int maxSeenDeliveries) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        this.maxTimestampSkew = maxTimestampSkew;
        this.clock = clock;
        this.seenDeliveryIds = Collections.synchronizedMap(new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
                return size() > maxSeenDeliveries;
            }
        });
    }

    public WebhookVerificationResult verify(String signature, String timestamp, String deliveryId, String rawBody) {
        if (!isFreshTimestamp(timestamp)) {
            return new WebhookVerificationResult(WebhookVerificationOutcome.STALE_TIMESTAMP, deliveryId);
        }
        if (!verifySignature(signature, timestamp, deliveryId, rawBody)) {
            return new WebhookVerificationResult(WebhookVerificationOutcome.INVALID_SIGNATURE, deliveryId);
        }
        if (!markSeen(deliveryId)) {
            return new WebhookVerificationResult(WebhookVerificationOutcome.DUPLICATE_DELIVERY, deliveryId);
        }
        return new WebhookVerificationResult(WebhookVerificationOutcome.ACCEPTED, deliveryId);
    }

    private boolean isFreshTimestamp(String timestamp) {
        try {
            Instant sent = Instant.ofEpochSecond(Long.parseLong(timestamp));
            Duration age = Duration.between(sent, clock.instant()).abs();
            return age.compareTo(maxTimestampSkew) <= 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean verifySignature(String signature, String timestamp, String deliveryId, String rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            String canonical = timestamp + "." + deliveryId + "." + rawBody;
            byte[] expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            byte[] provided = HexFormat.of().parseHex(signature);
            return MessageDigest.isEqual(expected, provided);
        } catch (NoSuchAlgorithmException | InvalidKeyException | IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean markSeen(String deliveryId) {
        return seenDeliveryIds.putIfAbsent(deliveryId, clock.instant()) == null;
    }
}
