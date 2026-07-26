package io.github.viniciusssantos.accountshield.webhook.internal;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Signs over {@code timestamp.deliveryId.rawBody} so receivers can verify the exact raw body. */
@Component
class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";

    String sign(String secret, String timestamp, String deliveryId, String rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            String canonical = timestamp + "." + deliveryId + "." + rawBody;
            byte[] digest = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("unable to sign webhook payload", exception);
        }
    }
}
