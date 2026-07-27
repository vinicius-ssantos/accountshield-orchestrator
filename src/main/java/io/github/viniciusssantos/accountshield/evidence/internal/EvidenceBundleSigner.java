package io.github.viniciusssantos.accountshield.evidence.internal;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// local/demo-only signer: an asymmetric keypair generated fresh per boot and never persisted,
// following the same posture as LocalJwtKeys. Unlike LocalJwtKeys (JWT-claims-shaped), this signs
// arbitrary bytes and is independently verifiable by anyone holding the manifest's own embedded
// public key -- no access to this running instance is required to verify a previously issued
// bundle, which is why verification is a static method taking the key as a parameter rather than
// an instance method bound to this signer's own keypair.
@Component
public class EvidenceBundleSigner {

    public static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String KEY_ALGORITHM = "RSA";

    private final KeyPair keyPair;

    public EvidenceBundleSigner(@Value("${accountshield.evidence.signing.key-size:2048}") int keySize) {
        this.keyPair = generateKeyPair(keySize);
    }

    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    public String sign(byte[] content) {
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(keyPair.getPrivate());
            signature.update(content);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("unable to sign evidence bundle content", exception);
        }
    }

    public static boolean verify(byte[] content, String signatureBase64, String publicKeyBase64) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
            PublicKey publicKey = KeyFactory.getInstance(KEY_ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(keyBytes));
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(content);
            return signature.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return false;
        }
    }

    private static KeyPair generateKeyPair(int keySize) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            generator.initialize(keySize);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("RSA key generation is not available", exception);
        }
    }
}
