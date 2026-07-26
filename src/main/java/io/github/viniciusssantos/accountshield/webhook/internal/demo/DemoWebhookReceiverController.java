package io.github.viniciusssantos.accountshield.webhook.internal.demo;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reference implementation of a webhook receiver: verifies the signature over the exact raw
 * body, rejects a stale {@code X-Webhook-Timestamp}, and rejects a previously seen {@code
 * X-Webhook-Delivery-Id} -- the receiver-side replay protection this issue's acceptance criteria
 * describe. Runs in-process rather than as a second deployable, matching this codebase's existing
 * "simulated provider" pattern (ADR 0004) rather than introducing a new service.
 */
@RestController
@RequestMapping("/demo/webhook-receiver")
class DemoWebhookReceiverController {

    private static final Logger log = LoggerFactory.getLogger(DemoWebhookReceiverController.class);
    private static final String ALGORITHM = "HmacSHA256";
    private static final int MAX_SEEN_DELIVERIES = 10_000;

    private final SecretKeySpec key;
    private final Duration maxTimestampSkew;
    private final Clock clock;
    private final Map<String, Instant> seenDeliveryIds = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
            return size() > MAX_SEEN_DELIVERIES;
        }
    });

    DemoWebhookReceiverController(
            @Value("${accountshield.webhook.demo-receiver.secret:accountshield-local-only-demo-receiver-secret}")
            String secret,
            @Value("${accountshield.webhook.demo-receiver.max-timestamp-skew:5m}") Duration maxTimestampSkew,
            @Qualifier("decisionClock") Clock clock) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        this.maxTimestampSkew = maxTimestampSkew;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestHeader("X-Webhook-Signature") String signature,
            @RequestHeader("X-Webhook-Timestamp") String timestamp,
            @RequestHeader("X-Webhook-Delivery-Id") String deliveryId,
            @RequestBody String rawBody) {
        if (!isFreshTimestamp(timestamp)) {
            log.warn("demo_webhook_stale_timestamp delivery_id={}", deliveryId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (!verifySignature(signature, timestamp, deliveryId, rawBody)) {
            log.warn("demo_webhook_invalid_signature delivery_id={}", deliveryId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!markSeen(deliveryId)) {
            log.info("demo_webhook_duplicate_delivery delivery_id={}", deliveryId);
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        log.info("demo_webhook_accepted delivery_id={}", deliveryId);
        return ResponseEntity.ok().build();
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
