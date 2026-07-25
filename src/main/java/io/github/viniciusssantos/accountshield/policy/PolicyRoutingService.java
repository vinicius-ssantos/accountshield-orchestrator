package io.github.viniciusssantos.accountshield.policy;

public interface PolicyRoutingService {

    /**
     * Resolves the policy key a given client's protection event should be evaluated against.
     * Throws {@link ActivePolicyUnavailableException} when no route exists — an unroutable
     * client/event combination is exactly as unavailable as a missing active policy version.
     */
    String resolvePolicyKey(String clientId, String eventType);
}
