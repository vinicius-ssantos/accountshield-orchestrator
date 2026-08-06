package io.github.viniciusssantos.accountshield.audit;

import java.time.Instant;

/** The tip of the audit hash chain at read time -- suitable for periodic external anchoring. */
public record AuditChainRootHash(long chainSequence, String recordHash, Instant decidedAt) {
}
