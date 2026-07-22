package io.github.viniciusssantos.accountshield.simulation;

import java.util.Optional;
import java.util.UUID;

public interface SimulationService {

    Optional<ReplayResult> replay(UUID protectionRequestId);

    ShadowEvaluationResult evaluateShadow(
            String policyKey,
            int riskScore,
            String candidatePolicyVersion);
}
