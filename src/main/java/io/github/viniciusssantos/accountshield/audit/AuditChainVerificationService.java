package io.github.viniciusssantos.accountshield.audit;

import java.util.Optional;
import java.util.UUID;

public interface AuditChainVerificationService {

    /**
     * Recomputes and checks every row's content hash and chain linkage over
     * {@code [fromSequenceInclusive, toSequenceInclusive]}, including verifying the first row in
     * range links correctly to whatever immediately precedes it outside the range.
     */
    AuditChainVerificationResult verifyRange(long fromSequenceInclusive, long toSequenceInclusive);

    /** The current tip of the chain, or empty if no chained record exists yet. */
    Optional<AuditChainRootHash> currentRootHash();

    /**
     * The chain proof for one specific decision, or empty if the decision does not exist or was
     * recorded before chaining existed (nullable chain columns).
     */
    Optional<AuditChainRecordProof> findProof(UUID decisionId);
}
