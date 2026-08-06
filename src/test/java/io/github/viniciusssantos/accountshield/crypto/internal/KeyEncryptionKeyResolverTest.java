package io.github.viniciusssantos.accountshield.crypto.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KeyEncryptionKeyResolverTest {

    private static final String VALID_32_BYTE_BASE64 = "D/d4CZZMPi+4f/3+7JoUrg0QEuVrXVNgQ8YTNigBcPk=";
    private static final String OTHER_VALID_32_BYTE_BASE64 = "q8lFeegq4dCZQxqe5z6HTOzFCiJI2f/iiB4gKn2ePGQ=";

    @Test
    void acceptsBase64Encoded32ByteKeyMaterial() {
        KeyEncryptionKeyResolver resolver = new KeyEncryptionKeyResolver(1, VALID_32_BYTE_BASE64, 0, "");

        assertThat(resolver.activeVersion()).isEqualTo(1);
        assertThat(resolver.keyForVersion(1).getAlgorithm()).isEqualTo("AES");
        assertThat(resolver.keyForVersion(1).getEncoded()).hasSize(KeyEncryptionKeyResolver.KEK_LENGTH_BYTES);
    }

    @Test
    void rejectsNonBase64Secret() {
        assertThatThrownBy(() -> new KeyEncryptionKeyResolver(1, "not-valid-base64!!!", 0, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid base64");
    }

    @Test
    void rejectsKeyMaterialThatDoesNotDecodeToExactly32Bytes() {
        // base64 of 16 bytes -- too short for AES-256
        String tooShort = "AAAAAAAAAAAAAAAAAAAAAA==";

        assertThatThrownBy(() -> new KeyEncryptionKeyResolver(1, tooShort, 0, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 32 bytes")
                .hasMessageContaining("got 16");
    }

    @Test
    void rejectsHumanReadablePassphraseThatHappensToBeValidBase64OfTheWrongLength() {
        // a passphrase-style value, deliberately accepted by base64 but decoding to 27 bytes, not 32
        assertThatThrownBy(() -> new KeyEncryptionKeyResolver(1, "c2hvcnQtcGFzc3BocmFzZQ==", 0, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 32 bytes");
    }

    @Test
    void resolvesPreviousVersionKeyWhenConfigured() {
        KeyEncryptionKeyResolver resolver = new KeyEncryptionKeyResolver(2, VALID_32_BYTE_BASE64, 1, OTHER_VALID_32_BYTE_BASE64);

        assertThat(resolver.keyForVersion(1).getEncoded()).hasSize(KeyEncryptionKeyResolver.KEK_LENGTH_BYTES);
        assertThat(resolver.keyForVersion(2).getEncoded()).hasSize(KeyEncryptionKeyResolver.KEK_LENGTH_BYTES);
    }

    @Test
    void throwsForAnUnconfiguredVersion() {
        KeyEncryptionKeyResolver resolver = new KeyEncryptionKeyResolver(1, VALID_32_BYTE_BASE64, 0, "");

        assertThatThrownBy(() -> resolver.keyForVersion(99))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version 99");
    }
}
