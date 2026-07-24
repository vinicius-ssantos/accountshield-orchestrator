package io.github.viniciusssantos.accountshield.challenge.internal;

import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class ChallengeCodecRegistry {

    private final Map<ChallengeType, SimulatedChallengeCodec> codecsByType;

    ChallengeCodecRegistry(NumericCodeCodec numericCodeCodec, WebAuthnAssertionCodec webAuthnAssertionCodec) {
        this.codecsByType = Map.of(
                ChallengeType.TOTP_SIMULATED, numericCodeCodec,
                ChallengeType.EMAIL_SIMULATED, numericCodeCodec,
                ChallengeType.WEBAUTHN_SIMULATED, webAuthnAssertionCodec);
    }

    String issue(ChallengeType type) {
        SimulatedChallengeCodec codec = codecsByType.get(type);
        if (codec == null) {
            throw new IllegalStateException("no simulated codec registered for challenge type: " + type);
        }
        return codec.issue();
    }
}
