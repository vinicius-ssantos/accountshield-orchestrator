package io.github.viniciusssantos.accountshield.webhook.internal;

import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
class WebhookSecretGenerator {

    private static final int SECRET_LENGTH_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    String generate() {
        byte[] bytes = new byte[SECRET_LENGTH_BYTES];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
