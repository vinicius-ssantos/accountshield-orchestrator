package io.github.viniciusssantos.accountshield.policy;

import java.util.UUID;

public interface PolicyLifecycleService {

    PolicyVersionSummary createDraft(CreatePolicyCommand command);

    PolicyVersionSummary validate(String policyKey, String version);

    PolicyVersionSummary activate(String policyKey, String version, UUID stepUpChallengeId, String actor);

    PolicyVersionSummary reject(String policyKey, String version);

    PolicyVersionSummary retire(String policyKey, String version, UUID stepUpChallengeId, String actor);

    UUID requestActivationStepUp(String policyKey, String version, String actor);

    UUID requestRetirementStepUp(String policyKey, String version, String actor);
}
