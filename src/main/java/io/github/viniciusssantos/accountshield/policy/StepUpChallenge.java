package io.github.viniciusssantos.accountshield.policy;

import java.util.UUID;

// contextId is exposed (unlike recovery's equivalent record) because policy step-up binds each
// challenge to a synthetic, server-derived UUID (action + policyKey + version), not a natural
// identifier the caller already has -- POST /challenges/{id}/verify requires the exact same
// contextId it was issued with, so a real HTTP client has no way to complete the flow without it.
public record StepUpChallenge(UUID challengeId, String simulatedCode, UUID contextId) {
}
