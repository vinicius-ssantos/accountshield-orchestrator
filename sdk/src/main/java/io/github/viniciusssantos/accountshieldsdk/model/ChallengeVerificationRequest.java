package io.github.viniciusssantos.accountshieldsdk.model;

import java.util.UUID;

/** Mirrors {@code POST /api/v1/challenges/{challengeId}/verify}'s request body exactly. */
public record ChallengeVerificationRequest(String providedCode, ChallengePurpose purpose, UUID contextId) {
}
