package io.github.viniciusssantos.accountshield.risk.internal;

import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskAssessment;
import io.github.viniciusssantos.accountshield.risk.RiskAssessmentService;
import io.github.viniciusssantos.accountshield.risk.RiskBand;
import io.github.viniciusssantos.accountshield.risk.RiskReason;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
final class DeterministicRiskAssessmentService implements RiskAssessmentService {

    static final String ALGORITHM_VERSION = "risk-rules-1.0";
    private static final int MAX_SCORE = 100;

    @Override
    public RiskAssessment assess(RiskSignals signals) {
        Objects.requireNonNull(signals, "signals must not be null");

        List<RiskReason> reasons = new ArrayList<>();
        int score = 0;
        score = addReason(reasons, score, "COMPROMISED_CREDENTIAL", signals.compromisedCredential() ? 40 : 0);
        score = addReason(reasons, score, "IMPOSSIBLE_TRAVEL", signals.impossibleTravel() ? 35 : 0);
        score = addReason(reasons, score, "FAILED_ATTEMPTS", Math.min(signals.failedAttempts() * 3, 30));
        score = addReason(
                reasons,
                score,
                networkReasonCode(signals.networkRiskLevel()),
                networkContribution(signals.networkRiskLevel()));
        score = addReason(reasons, score, "NEW_DEVICE", signals.newDevice() ? 15 : 0);

        return new RiskAssessment(score, RiskBand.fromScore(score), ALGORITHM_VERSION, reasons);
    }

    private int addReason(List<RiskReason> reasons, int currentScore, String code, int requestedContribution) {
        int appliedContribution = Math.min(requestedContribution, MAX_SCORE - currentScore);
        if (appliedContribution > 0) {
            reasons.add(new RiskReason(code, appliedContribution));
        }
        return currentScore + appliedContribution;
    }

    private int networkContribution(NetworkRiskLevel level) {
        return switch (level) {
            case LOW -> 0;
            case MEDIUM -> 10;
            case HIGH -> 20;
        };
    }

    private String networkReasonCode(NetworkRiskLevel level) {
        return switch (level) {
            case LOW -> "NETWORK_RISK_LOW";
            case MEDIUM -> "NETWORK_RISK_MEDIUM";
            case HIGH -> "NETWORK_RISK_HIGH";
        };
    }
}
