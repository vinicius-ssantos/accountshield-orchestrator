package io.github.viniciusssantos.accountshieldsdk.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs a payload exactly the way the AccountShield server does (mirrors {@code
 * webhook.internal.WebhookSigner}). Real webhook deliveries are always signed by the server, not
 * by an SDK consumer -- this class exists so a consumer can exercise
 * {@link WebhookSignatureVerifier} end to end with a locally-constructed sample payload (this is
 * exactly what the {@code accountshield-demo} module's webhook-timeline demonstration does), not
 * as a general-purpose signing utility for production traffic.
 */
public final class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";

    public String sign(String secret, String timestamp, String deliveryId, String rawBody) {
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
