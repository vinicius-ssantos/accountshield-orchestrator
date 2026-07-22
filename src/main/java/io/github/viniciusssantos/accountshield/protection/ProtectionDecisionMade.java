package io.github.viniciusssantos.accountshield.protection;

import java.time.Instant;
import java.util.UUID;

public record ProtectionDecisionMade(
        UUID decisionId,
        UUID protectionRequestId,
        String accountReference,
        String outcome,
        int riskScore,
        String policyKey,
        String policyVersion,
        Instant decidedAt) {
}
