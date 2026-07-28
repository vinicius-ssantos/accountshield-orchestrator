package io.github.viniciusssantos.accountshieldsdk.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Mirrors {@code POST /api/v1/protection-decisions}' 201 response body exactly. */
public record ProtectionDecisionResponse(
        UUID decisionId,
        UUID protectionRequestId,
        UUID recoveryAuthorizationId,
        ProtectionOutcome outcome,
        int riskScore,
        RiskBand riskBand,
        String algorithmVersion,
        String policyKey,
        String policyVersion,
        List<Reason> reasons,
        Instant decidedAt,
        Challenge challenge,
        boolean degraded,
        String degradationReason) {

    public record Reason(String code, int contribution) {
    }

    public record Challenge(UUID challengeId, ChallengeType challengeType, Instant expiresAt) {
    }
}
