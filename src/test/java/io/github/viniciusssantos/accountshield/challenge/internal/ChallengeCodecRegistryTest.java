package io.github.viniciusssantos.accountshield.challenge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import org.junit.jupiter.api.Test;

class ChallengeCodecRegistryTest {

    private final ChallengeCodecRegistry registry =
            new ChallengeCodecRegistry(new NumericCodeCodec(), new WebAuthnAssertionCodec());

    @Test
    void totpAndEmailIssueSixDigitNumericCodes() {
        assertThat(registry.issue(ChallengeType.TOTP_SIMULATED)).matches("\\d{6}");
        assertThat(registry.issue(ChallengeType.EMAIL_SIMULATED)).matches("\\d{6}");
    }

    @Test
    void webAuthnIsNotRepresentedAsASixDigitCode() {
        String assertion = registry.issue(ChallengeType.WEBAUTHN_SIMULATED);

        assertThat(assertion).doesNotMatch("\\d{6}");
        assertThat(assertion).hasSize(32);
    }

    @Test
    void issuedValuesAreNotTriviallyPredictable() {
        String first = registry.issue(ChallengeType.TOTP_SIMULATED);
        String second = registry.issue(ChallengeType.TOTP_SIMULATED);

        assertThat(first).isNotEqualTo(second);
    }
}
