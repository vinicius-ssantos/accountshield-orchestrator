package io.github.viniciusssantos.accountshield.audit;

import java.time.Instant;
import java.util.List;

public record AuditChainIntegrityFailed(
        long fromSequence, long toSequence, List<AuditChainBreak> breaks, Instant detectedAt) {

    public AuditChainIntegrityFailed {
        breaks = List.copyOf(breaks);
    }
}
