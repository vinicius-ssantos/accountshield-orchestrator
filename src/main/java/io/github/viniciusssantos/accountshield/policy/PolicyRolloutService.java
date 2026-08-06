package io.github.viniciusssantos.accountshield.policy;

import java.util.Optional;
import java.util.UUID;

public interface PolicyRolloutService {

    StepUpChallenge requestRolloutStepUp(String policyKey, String candidateVersion, String actor);

    PolicyRollout startRollout(
            String policyKey, String candidateVersion, int rolloutPercentage, UUID stepUpChallengeId, String actor);

    StepUpChallenge requestPercentageUpdateStepUp(String policyKey, String actor);

    PolicyRollout updatePercentage(
            String policyKey, int rolloutPercentage, UUID stepUpChallengeId, String actor);

    PolicyRollout rollback(String policyKey, String actor);

    Optional<PolicyRollout> findActiveRollout(String policyKey);
}
