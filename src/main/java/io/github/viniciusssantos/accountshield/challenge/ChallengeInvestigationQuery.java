package io.github.viniciusssantos.accountshield.challenge;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only, privacy-minimized challenge projection for operator investigation. */
public interface ChallengeInvestigationQuery {

    List<ChallengeInvestigationView> findByContextId(UUID contextId);

    record ChallengeInvestigationView(
            UUID reference,
            String challengeType,
            String purpose,
            String status,
            Instant createdAt,
            Instant expiresAt,
            Instant consumedAt) {
    }
}
