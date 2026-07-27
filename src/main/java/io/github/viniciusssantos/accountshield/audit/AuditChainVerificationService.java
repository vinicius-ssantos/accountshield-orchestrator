package io.github.viniciusssantos.accountshield.audit;

import java.util.Optional;

public interface AuditChainVerificationService {

    /**
     * Recomputes and checks every row's content hash and chain linkage over
     * {@code [fromSequenceInclusive, toSequenceInclusive]}, including verifying the first row in
     * range links correctly to whatever immediately precedes it outside the range.
     */
    AuditChainVerificationResult verifyRange(long fromSequenceInclusive, long toSequenceInclusive);

    /** The current tip of the chain, or empty if no chained record exists yet. */
    Optional<AuditChainRootHash> currentRootHash();
}
