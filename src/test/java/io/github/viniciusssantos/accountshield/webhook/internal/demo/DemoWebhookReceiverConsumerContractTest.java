package io.github.viniciusssantos.accountshield.webhook.internal.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.viniciusssantos.accountshield.contracts.IntegrationEventFixtures;
import io.github.viniciusssantos.accountshield.outbox.IntegrationEventEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer contract test for issue #52: proves a real, documented event-envelope fixture -- not a
 * toy {@code {"a":1}} body -- both (a) is accepted end-to-end by a real webhook consumer
 * ({@link DemoWebhookReceiverController}, this codebase's reference receiver) and (b) deserializes
 * back into the actual {@code IntegrationEventEnvelope} type with the expected field values,
 * satisfying "runtime examples match the published contracts." There is no published SDK package
 * in this codebase (confirmed absent); this is the proportional substitute for a consumer-SDK
 * contract test.
 */
class DemoWebhookReceiverConsumerContractTest {

    private static final String SECRET = "test-demo-receiver-secret";
    private static final ObjectMapper OBJECT_MAPPER = IntegrationEventFixtures.objectMapper();

    private final Clock clock = Clock.fixed(IntegrationEventFixtures.FIXED_INSTANT, ZoneOffset.UTC);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new DemoWebhookReceiverController(SECRET, Duration.ofMinutes(5), clock))
            .build();

    @Test
    void realEventFixtureIsAcceptedAndRoundTripsThroughTheRealEnvelopeType() throws Exception {
        IntegrationEventEnvelope envelope = IntegrationEventFixtures.protectionDecisionMade();
        String rawBody = OBJECT_MAPPER.writeValueAsString(envelope);
        String timestamp = String.valueOf(IntegrationEventFixtures.FIXED_INSTANT.getEpochSecond());
        String deliveryId = UUID.randomUUID().toString();

        mockMvc.perform(post("/demo/webhook-receiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", sign(timestamp, deliveryId, rawBody))
                        .header("X-Webhook-Timestamp", timestamp)
                        .header("X-Webhook-Delivery-Id", deliveryId)
                        .content(rawBody))
                .andExpect(status().isOk());

        IntegrationEventEnvelope received = OBJECT_MAPPER.readValue(rawBody, IntegrationEventEnvelope.class);
        assertThat(received.schemaVersion()).isEqualTo(envelope.schemaVersion());
        assertThat(received.eventId()).isEqualTo(envelope.eventId());
        assertThat(received.correlationId()).isEqualTo(envelope.correlationId());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) received.data();
        assertThat(data).containsEntry("outcome", "ALLOW");
        assertThat(data).doesNotContainKey("accountReference");
        assertThat(data).containsKey("subjectToken");
    }

    private String sign(String timestamp, String deliveryId, String rawBody) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String canonical = timestamp + "." + deliveryId + "." + rawBody;
        byte[] digest = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
