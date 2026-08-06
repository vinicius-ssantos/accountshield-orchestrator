package io.github.viniciusssantos.accountshield.webhook.internal.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DemoWebhookReceiverControllerTest {

    private static final String SECRET = "test-demo-receiver-secret";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DemoWebhookReceiverController(SECRET, Duration.ofMinutes(5), clock))
                .build();
    }

    @Test
    void acceptsAFreshValidlySignedDelivery() throws Exception {
        String timestamp = String.valueOf(NOW.getEpochSecond());
        String deliveryId = "delivery-1";
        String body = "{\"a\":1}";

        mockMvc.perform(post("/demo/webhook-receiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", sign(timestamp, deliveryId, body))
                        .header("X-Webhook-Timestamp", timestamp)
                        .header("X-Webhook-Delivery-Id", deliveryId)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsAStaleTimestamp() throws Exception {
        String staleTimestamp = String.valueOf(NOW.minus(Duration.ofHours(1)).getEpochSecond());
        String deliveryId = "delivery-2";
        String body = "{\"a\":1}";

        mockMvc.perform(post("/demo/webhook-receiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", sign(staleTimestamp, deliveryId, body))
                        .header("X-Webhook-Timestamp", staleTimestamp)
                        .header("X-Webhook-Delivery-Id", deliveryId)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnInvalidSignature() throws Exception {
        String timestamp = String.valueOf(NOW.getEpochSecond());
        String deliveryId = "delivery-3";
        String body = "{\"a\":1}";

        mockMvc.perform(post("/demo/webhook-receiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", "0".repeat(64))
                        .header("X-Webhook-Timestamp", timestamp)
                        .header("X-Webhook-Delivery-Id", deliveryId)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsADuplicateDeliveryId() throws Exception {
        String timestamp = String.valueOf(NOW.getEpochSecond());
        String deliveryId = "delivery-4";
        String body = "{\"a\":1}";
        String signature = sign(timestamp, deliveryId, body);

        mockMvc.perform(post("/demo/webhook-receiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", signature)
                        .header("X-Webhook-Timestamp", timestamp)
                        .header("X-Webhook-Delivery-Id", deliveryId)
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/demo/webhook-receiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", signature)
                        .header("X-Webhook-Timestamp", timestamp)
                        .header("X-Webhook-Delivery-Id", deliveryId)
                        .content(body))
                .andExpect(status().isConflict());
    }

    private String sign(String timestamp, String deliveryId, String rawBody) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String canonical = timestamp + "." + deliveryId + "." + rawBody;
        byte[] digest = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
