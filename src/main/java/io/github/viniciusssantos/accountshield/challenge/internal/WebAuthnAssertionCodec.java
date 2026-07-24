package io.github.viniciusssantos.accountshield.challenge.internal;

import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
class WebAuthnAssertionCodec implements SimulatedChallengeCodec {

    private static final int ASSERTION_BYTES = 16;

    private final SecureRandom random = new SecureRandom();

    @Override
    public String issue() {
        byte[] assertion = new byte[ASSERTION_BYTES];
        random.nextBytes(assertion);
        return HexFormat.of().formatHex(assertion);
    }
}
