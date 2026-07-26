package io.github.viniciusssantos.accountshield.webhook.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebhookSignerTest {

    private final WebhookSigner signer = new WebhookSigner();

    @Test
    void sameInputsProduceTheSameSignature() {
        String first = signer.sign("secret", "1700000000", "delivery-1", "{\"a\":1}");
        String second = signer.sign("secret", "1700000000", "delivery-1", "{\"a\":1}");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentRawBodyProducesADifferentSignature() {
        String first = signer.sign("secret", "1700000000", "delivery-1", "{\"a\":1}");
        String second = signer.sign("secret", "1700000000", "delivery-1", "{\"a\":2}");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void differentSecretProducesADifferentSignature() {
        String first = signer.sign("secret-one", "1700000000", "delivery-1", "{\"a\":1}");
        String second = signer.sign("secret-two", "1700000000", "delivery-1", "{\"a\":1}");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void differentDeliveryIdProducesADifferentSignature() {
        String first = signer.sign("secret", "1700000000", "delivery-1", "{\"a\":1}");
        String second = signer.sign("secret", "1700000000", "delivery-2", "{\"a\":1}");

        assertThat(first).isNotEqualTo(second);
    }
}
