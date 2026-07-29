package io.github.viniciusssantos.accountshield.recovery;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Read-only, privacy-minimized recovery projection for operator investigation. */
public interface RecoveryInvestigationQuery {

    Optional<RecoveryInvestigationView> findByDecisionId(UUID decisionId);

    record RecoveryInvestigationView(
            UUID reference,
            String directive,
            Instant issuedAt,
            Instant expiresAt,
            Instant consumedAt) {

        public String status() {
            return consumedAt == null ? "ISSUED" : "CONSUMED";
        }
    }
}
