package io.github.viniciusssantos.accountshield.recovery;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Privacy-minimized read port for the security-operations recovery queue and detail. */
public interface RecoveryOperationsQuery {

    int DEFAULT_PAGE_SIZE = 25;
    int MAX_PAGE_SIZE = 100;
    Duration MAX_TIME_WINDOW = Duration.ofDays(31);

    RecoveryPage search(RecoveryCriteria criteria);

    Optional<RecoveryDetail> investigate(String recoveryReference);

    record RecoveryCriteria(
            String status,
            String classification,
            String eventType,
            String reviewState,
            Instant initiatedFrom,
            Instant initiatedTo,
            Instant eligibleFrom,
            Instant eligibleTo,
            Integer minimumRiskScore,
            Integer maximumRiskScore,
            String cursor,
            int pageSize) {

        public RecoveryCriteria {
            status = optionalBounded(status, "status", 32);
            classification = optionalBounded(classification, "classification", 32);
            eventType = optionalBounded(eventType, "eventType", 32);
            reviewState = optionalBounded(reviewState, "reviewState", 32);
            cursor = optionalBounded(cursor, "cursor", 256);
            validateWindow(initiatedFrom, initiatedTo, "initiated");
            validateWindow(eligibleFrom, eligibleTo, "eligible");
            validateRiskScore(minimumRiskScore, "minimumRiskScore");
            validateRiskScore(maximumRiskScore, "maximumRiskScore");
            if (minimumRiskScore != null
                    && maximumRiskScore != null
                    && minimumRiskScore > maximumRiskScore) {
                throw new IllegalArgumentException(
                        "minimumRiskScore must be less than or equal to maximumRiskScore");
            }
            if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException(
                        "pageSize must be between 1 and " + MAX_PAGE_SIZE);
            }
        }

        private static void validateWindow(Instant from, Instant to, String name) {
            if (from == null || to == null) {
                return;
            }
            if (!from.isBefore(to)) {
                throw new IllegalArgumentException(name + "From must be before " + name + "To");
            }
            if (Duration.between(from, to).compareTo(MAX_TIME_WINDOW) > 0) {
                throw new IllegalArgumentException(
                        name + " time window must not exceed " + MAX_TIME_WINDOW.toDays() + " days");
            }
        }

        private static void validateRiskScore(Integer value, String name) {
            if (value != null && (value < 0 || value > 100)) {
                throw new IllegalArgumentException(name + " must be between 0 and 100");
            }
        }
    }

    record RecoverySummary(
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
            String originatingDecisionReference,
            String reviewState,
            boolean challengeExpected) {

        public RecoverySummary {
            recoveryReference = requireText(recoveryReference, "recoveryReference");
            maskedSubjectReference = requireText(maskedSubjectReference, "maskedSubjectReference");
            eventType = requireText(eventType, "eventType");
            status = requireText(status, "status");
            classification = requireText(classification, "classification");
            classificationRuleVersion = requireText(
                    classificationRuleVersion, "classificationRuleVersion");
            if (riskScore < 0 || riskScore > 100) {
                throw new IllegalArgumentException("riskScore must be between 0 and 100");
            }
            Objects.requireNonNull(initiatedAt, "initiatedAt must not be null");
            Objects.requireNonNull(updatedAt, "updatedAt must not be null");
            originatingDecisionReference = requireText(
                    originatingDecisionReference, "originatingDecisionReference");
            reviewState = requireText(reviewState, "reviewState");
        }
    }

    record ChallengeEvidence(
            String reference,
            String challengeType,
            String purpose,
            String status,
            Instant createdAt,
            Instant expiresAt,
            Instant consumedAt) {

        public ChallengeEvidence {
            reference = requireText(reference, "reference");
            challengeType = requireText(challengeType, "challengeType");
            purpose = requireText(purpose, "purpose");
            status = requireText(status, "status");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        }
    }

    enum SectionAvailability {
        AVAILABLE,
        NOT_APPLICABLE,
        UNAVAILABLE
    }

    record RecoveryDetail(
            RecoverySummary recovery,
            String protectionRequestReference,
            boolean reviewerPresent,
            List<ChallengeEvidence> challenges,
            SectionAvailability challengeAvailability,
            boolean partial) {

        public RecoveryDetail {
            Objects.requireNonNull(recovery, "recovery must not be null");
            protectionRequestReference = requireText(
                    protectionRequestReference, "protectionRequestReference");
            challenges = List.copyOf(Objects.requireNonNull(challenges, "challenges must not be null"));
            Objects.requireNonNull(challengeAvailability, "challengeAvailability must not be null");
            if (challengeAvailability == SectionAvailability.AVAILABLE && challenges.isEmpty()) {
                throw new IllegalArgumentException(
                        "challenge evidence is required when challengeAvailability is AVAILABLE");
            }
        }
    }

    record RecoveryPage(
            List<RecoverySummary> recoveries,
            String nextCursor,
            int pageSize,
            boolean hasMore) {

        public RecoveryPage {
            recoveries = List.copyOf(Objects.requireNonNull(recoveries, "recoveries must not be null"));
            if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException(
                        "pageSize must be between 1 and " + MAX_PAGE_SIZE);
            }
            if (hasMore && (nextCursor == null || nextCursor.isBlank())) {
                throw new IllegalArgumentException("nextCursor is required when hasMore is true");
            }
            if (!hasMore) {
                nextCursor = null;
            }
        }
    }

    private static String optionalBounded(String value, String name, int maximumLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    name + " must contain between 1 and " + maximumLength + " characters when provided");
        }
        return normalized;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
