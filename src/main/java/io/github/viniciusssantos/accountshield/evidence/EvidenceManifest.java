package io.github.viniciusssantos.accountshield.evidence;

import java.time.Instant;
import java.util.UUID;

/**
 * Provenance and signature over an {@link EvidenceBundleContent}'s canonical JSON. The signing
 * public key travels with the manifest so a bundle is independently verifiable by anyone holding
 * only the bundle itself -- no access to the issuing system is required.
 */
public record EvidenceManifest(
        String bundleSchemaVersion,
        UUID decisionId,
        UUID protectionRequestId,
        Instant generatedAt,
        String exportedBy,
        String exportReason,
        String contentHashAlgorithm,
        String contentHash,
        String signatureAlgorithm,
        String signature,
        String signingPublicKey) {
}
