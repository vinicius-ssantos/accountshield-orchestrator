package io.github.viniciusssantos.accountshield.webhook.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WebhookSecretCipherTest {

    private final WebhookSecretCipher cipher = new WebhookSecretCipher("D/d4CZZMPi+4f/3+7JoUrg0QEuVrXVNgQ8YTNigBcPk=");

    @Test
    void encryptsAndDecryptsRoundTrip() {
        WebhookSecretCipher.EncryptedSecret encrypted = cipher.encrypt("my-webhook-secret");

        assertThat(cipher.decrypt(encrypted.ciphertext(), encrypted.nonce())).isEqualTo("my-webhook-secret");
    }

    @Test
    void repeatedEncryptionsOfTheSamePlaintextProduceDifferentCiphertext() {
        WebhookSecretCipher.EncryptedSecret first = cipher.encrypt("my-webhook-secret");
        WebhookSecretCipher.EncryptedSecret second = cipher.encrypt("my-webhook-secret");

        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(cipher.decrypt(first.ciphertext(), first.nonce())).isEqualTo("my-webhook-secret");
        assertThat(cipher.decrypt(second.ciphertext(), second.nonce())).isEqualTo("my-webhook-secret");
    }

    @Test
    void decryptingWithADifferentKeyFails() {
        WebhookSecretCipher other = new WebhookSecretCipher("q8lFeegq4dCZQxqe5z6HTOzFCiJI2f/iiB4gKn2ePGQ=");
        WebhookSecretCipher.EncryptedSecret encrypted = cipher.encrypt("my-webhook-secret");

        assertThatThrownBy(() -> other.decrypt(encrypted.ciphertext(), encrypted.nonce()))
                .isInstanceOf(IllegalStateException.class);
    }
}
