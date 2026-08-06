package io.github.viniciusssantos.accountshield.policy;

import java.util.UUID;

public interface PolicyLifecycleService {

    PolicyVersionSummary createDraft(CreatePolicyCommand command, String actor);

    PolicyVersionSummary validate(String policyKey, String version, String actor);

    StepUpChallenge requestApprovalStepUp(String policyKey, String version, String actor);

    PolicyVersionSummary approve(
            String policyKey, String version, UUID stepUpChallengeId, String actor, String reason);

    PolicyVersionSummary activate(String policyKey, String version, UUID stepUpChallengeId, String actor);

    PolicyVersionSummary reject(String policyKey, String version);

    PolicyVersionSummary retire(String policyKey, String version, UUID stepUpChallengeId, String actor);

    StepUpChallenge requestActivationStepUp(String policyKey, String version, String actor);

    StepUpChallenge requestRetirementStepUp(String policyKey, String version, String actor);
}
