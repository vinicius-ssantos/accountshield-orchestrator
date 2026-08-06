package io.github.viniciusssantos.accountshield.outbox;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AccountPseudonymizer {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public AccountPseudonymizer(
            @Value("${accountshield.privacy.pseudonym-secret:accountshield-local-only-pseudonym-secret}")
            String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String pseudonymize(String accountReference) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(accountReference.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("unable to pseudonymize account reference", exception);
        }
    }
}
