package io.github.viniciusssantos.accountshield.protection.internal.web;

import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.risk.RiskBand;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProtectionDecisionResponse(
        UUID decisionId,
        UUID protectionRequestId,
        ProtectionOutcome outcome,
        int riskScore,
        RiskBand riskBand,
        String algorithmVersion,
        String policyKey,
        String policyVersion,
        List<ReasonResponse> reasons,
        Instant decidedAt,
        ChallengeResponse challenge) {

    static ProtectionDecisionResponse from(ProtectionDecisionResult result) {
        ChallengeResponse challengeResponse = result.challenge() != null
                ? new ChallengeResponse(
                        result.challenge().challengeId(),
                        result.challenge().challengeType(),
                        result.challenge().expiresAt())
                : null;
        return new ProtectionDecisionResponse(
                result.decisionId(),
                result.protectionRequestId(),
                result.outcome(),
                result.riskScore(),
                result.riskBand(),
                result.algorithmVersion(),
                result.policyKey(),
                result.policyVersion(),
                result.reasons().stream()
                        .map(reason -> new ReasonResponse(reason.code(), reason.contribution()))
                        .toList(),
                result.decidedAt(),
                challengeResponse);
    }

    public record ReasonResponse(String code, int contribution) {
    }

    public record ChallengeResponse(UUID challengeId, ChallengeType challengeType, Instant expiresAt) {
    }
}
