package io.github.viniciusssantos.accountshieldsdk.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors {@code RecoveryController.RecoveryResponse} exactly -- note {@code eventType},
 * {@code status}, and {@code classification} are plain strings on the wire (the server serializes
 * its enums via {@code .name()}), not typed enums, so the SDK does not need its own copies of those
 * three server-internal enum types.
 */
public record RecoveryResponse(
        UUID recoveryId,
        String accountReference,
        String eventType,
        String status,
        String classification,
        String classificationRuleVersion,
        UUID identityChallengeId,
        Instant initiatedAt,
        Instant updatedAt,
        Instant eligibleAfter,
        UUID authorizationId,
        UUID protectionRequestId,
        UUID originatingDecisionId) {
}
