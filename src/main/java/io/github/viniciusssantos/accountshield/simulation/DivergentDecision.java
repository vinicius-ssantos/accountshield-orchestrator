package io.github.viniciusssantos.accountshield.simulation;

import io.github.viniciusssantos.accountshield.risk.RiskReason;
import java.util.List;
import java.util.UUID;

public record DivergentDecision(
        UUID protectionRequestId,
        String redactedAccountReference,
        String originalOutcome,
        String candidateOutcome,
        int riskScore,
        List<RiskReason> originalReasons) {

    public DivergentDecision {
        originalReasons = List.copyOf(originalReasons);
    }
}
