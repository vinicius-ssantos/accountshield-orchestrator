package io.github.viniciusssantos.accountshield.evidence;

import java.util.Optional;

public interface EvidenceBundleService {

    /**
     * Builds, signs, and audits the export of a redacted evidence bundle for the decision
     * belonging to {@code command.protectionRequestId()}. Empty if no decision was ever recorded
     * for that protection request.
     */
    Optional<EvidenceBundle> exportBundle(EvidenceExportCommand command);

    /**
     * Recomputes the content hash and verifies the signature against the manifest's own embedded
     * public key -- self-contained, requiring no access to the issuing system's live state.
     */
    EvidenceVerificationResult verify(EvidenceBundle bundle);
}
