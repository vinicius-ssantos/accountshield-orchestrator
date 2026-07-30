package io.github.viniciusssantos.accountshield.recovery;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Privacy-minimized read port for one authorized recovery investigation detail.
 *
 * <p>This contract deliberately does not expose raw account references, challenge material,
 * provider payloads, or persistence entities. Cross-module references (originating decision,
 * protection request) are masked because operators use {@link #investigate} by opaque recovery
 * reference and do not need those identifiers to pivot directly into other modules.</p>
 */
public interface RecoveryFlowDetailQuery {

    Optional<RecoveryFlowDetail> investigate(String recoveryReference);

    enum SectionAvailability {
        AVAILABLE,
        NOT_APPLICABLE,
        UNAVAILABLE
    }

    record RecoveryChallengeSummary(
            String reference,
            String challengeType,
            String purpose,
            String status,
            Instant createdAt,
            Instant expiresAt,
            Instant consumedAt) {
    }

    record RecoveryFlowDetail(
            String recoveryReference,
            String maskedSubjectReference,
            String eventType,
            String status,
            boolean terminal,
            String classification,
            String classificationRuleVersion,
            int riskScore,
            Instant initiatedAt,
            Instant updatedAt,
            Instant eligibleAfter,
            Instant terminalAt,
            String reviewer,
            String maskedOriginatingDecisionReference,
            String maskedProtectionRequestReference,
            List<RecoveryChallengeSummary> challenges,
            SectionAvailability challengeSection,
            boolean partial) {

        public RecoveryFlowDetail {
            recoveryReference = requireText(recoveryReference, "recoveryReference");
            maskedSubjectReference = requireText(maskedSubjectReference, "maskedSubjectReference");
            eventType = requireText(eventType, "eventType");
            status = requireText(status, "status");
            classification = requireText(classification, "classification");
            classificationRuleVersion = requireText(classificationRuleVersion, "classificationRuleVersion");
            if (riskScore < 0 || riskScore > 100) {
                throw new IllegalArgumentException("riskScore must be between 0 and 100");
            }
            Objects.requireNonNull(initiatedAt, "initiatedAt must not be null");
            Objects.requireNonNull(updatedAt, "updatedAt must not be null");
            maskedOriginatingDecisionReference =
                    requireText(maskedOriginatingDecisionReference, "maskedOriginatingDecisionReference");
            maskedProtectionRequestReference =
                    requireText(maskedProtectionRequestReference, "maskedProtectionRequestReference");
            challenges = List.copyOf(Objects.requireNonNull(challenges, "challenges must not be null"));
            Objects.requireNonNull(challengeSection, "challengeSection must not be null");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
