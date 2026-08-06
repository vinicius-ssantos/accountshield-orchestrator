package io.github.viniciusssantos.accountshield.audit;

/**
 * A single chained record's own linkage and content hash -- the minimal evidence needed to prove
 * one specific decision is part of the tamper-evident chain, without verifying an entire range.
 */
public record AuditChainRecordProof(
        long chainSequence,
        String previousHash,
        String recordHash,
        String hashAlgorithm,
        String canonicalSchemaVersion) {
}
