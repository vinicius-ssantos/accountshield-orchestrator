package io.github.viniciusssantos.accountshield.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record DecisionTraceCommand(
        UUID decisionId,
        UUID protectionRequestId,
        String accountReference,
        String requestFingerprint,
        String algorithmVersion,
        String policyKey,
        String policyVersion,
        String outcome,
        int riskScore,
        Map<String, Object> normalizedContext,
        Instant decidedAt,
        List<DecisionReasonContribution> reasons) {

    public DecisionTraceCommand {
        Objects.requireNonNull(decisionId, "decisionId must not be null");
        Objects.requireNonNull(protectionRequestId, "protectionRequestId must not be null");
        accountReference = requireBounded(accountReference, "accountReference", 128);
        requestFingerprint = requireBounded(requestFingerprint, "requestFingerprint", 64);
        algorithmVersion = requireBounded(algorithmVersion, "algorithmVersion", 40);
        policyKey = requireBounded(policyKey, "policyKey", 100);
        policyVersion = requireBounded(policyVersion, "policyVersion", 40);
        outcome = requireBounded(outcome, "outcome", 32);
        if (riskScore < 0 || riskScore > 100) {
            throw new IllegalArgumentException("riskScore must be between 0 and 100");
        }
        normalizedContext = Map.copyOf(Objects.requireNonNull(normalizedContext, "normalizedContext must not be null"));
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons must not be null"));
    }

    private static String requireBounded(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must contain between 1 and " + maxLength + " characters");
        }
        return value;
    }
}
