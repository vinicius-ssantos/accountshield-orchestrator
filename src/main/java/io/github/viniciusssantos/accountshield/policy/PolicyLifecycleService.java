package io.github.viniciusssantos.accountshield.policy;

public interface PolicyLifecycleService {

    PolicyVersionSummary createDraft(CreatePolicyCommand command);

    PolicyVersionSummary validate(String policyKey, String version);

    PolicyVersionSummary activate(String policyKey, String version);

    PolicyVersionSummary reject(String policyKey, String version);

    PolicyVersionSummary retire(String policyKey, String version);
}
