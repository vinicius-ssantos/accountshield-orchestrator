package io.github.viniciusssantos.accountshield.audit;

import java.util.List;

public record AuditChainVerificationResult(long recordsChecked, boolean valid, List<AuditChainBreak> breaks) {

    public AuditChainVerificationResult {
        breaks = List.copyOf(breaks);
    }
}
