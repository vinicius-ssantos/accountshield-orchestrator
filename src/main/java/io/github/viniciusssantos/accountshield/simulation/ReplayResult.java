package io.github.viniciusssantos.accountshield.simulation;

import io.github.viniciusssantos.accountshield.risk.RiskBand;
import io.github.viniciusssantos.accountshield.risk.RiskReason;
import java.util.List;
import java.util.UUID;

public record ReplayResult(
        UUID protectionRequestId,
        boolean matches,
        String originalOutcome,
        String replayedOutcome,
        int originalRiskScore,
        int replayedRiskScore,
        RiskBand originalRiskBand,
        RiskBand replayedRiskBand,
        List<RiskReason> originalReasons,
        List<RiskReason> replayedReasons,
        String policyKey,
        String policyVersion,
        String algorithmVersion,
        List<String> mismatches) {

    public ReplayResult {
        originalReasons = List.copyOf(originalReasons);
        replayedReasons = List.copyOf(replayedReasons);
        mismatches = List.copyOf(mismatches);
    }
}
