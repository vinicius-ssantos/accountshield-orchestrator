package io.github.viniciusssantos.accountshield.evidence.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EvidenceBundleSignerTest {

    private final EvidenceBundleSigner signer = new EvidenceBundleSigner(2048);

    @Test
    void signatureVerifiesAgainstTheSignersOwnPublicKey() {
        byte[] content = "evidence-content".getBytes(StandardCharsets.UTF_8);

        String signature = signer.sign(content);

        assertThat(EvidenceBundleSigner.verify(content, signature, signer.publicKeyBase64())).isTrue();
    }

    @Test
    void verifyFailsWhenContentIsTampered() {
        byte[] content = "evidence-content".getBytes(StandardCharsets.UTF_8);
        byte[] tampered = "evidence-CONTENT".getBytes(StandardCharsets.UTF_8);

        String signature = signer.sign(content);

        assertThat(EvidenceBundleSigner.verify(tampered, signature, signer.publicKeyBase64())).isFalse();
    }

    @Test
    void verifyFailsAgainstADifferentSignersPublicKey() {
        byte[] content = "evidence-content".getBytes(StandardCharsets.UTF_8);
        EvidenceBundleSigner otherSigner = new EvidenceBundleSigner(2048);

        String signature = signer.sign(content);

        assertThat(EvidenceBundleSigner.verify(content, signature, otherSigner.publicKeyBase64())).isFalse();
    }

    @Test
    void verifyFailsOnGarbageSignatureOrKeyInsteadOfThrowing() {
        byte[] content = "evidence-content".getBytes(StandardCharsets.UTF_8);

        assertThat(EvidenceBundleSigner.verify(content, "not-base64!!", signer.publicKeyBase64())).isFalse();
        assertThat(EvidenceBundleSigner.verify(content, signer.sign(content), "not-base64!!")).isFalse();
    }
}
