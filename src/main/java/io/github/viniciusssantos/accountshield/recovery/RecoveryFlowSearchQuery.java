package io.github.viniciusssantos.accountshield.recovery;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Privacy-minimized, deterministic read port for the operator recovery queue.
 *
 * <p>This contract deliberately does not expose raw account references, challenge material,
 * provider payloads, or persistence entities.</p>
 */
public interface RecoveryFlowSearchQuery {

    int DEFAULT_PAGE_SIZE = 25;
    int MAX_PAGE_SIZE = 100;
    Duration MAX_TIME_WINDOW = Duration.ofDays(31);

    RecoveryFlowSearchPage search(RecoveryFlowSearchCriteria criteria);

    record RecoveryFlowSearchCriteria(
            String status,
            String classification,
            String eventType,
            Instant initiatedFrom,
            Instant initiatedTo,
            Instant eligibleBefore,
            Instant eligibleAfter,
            Integer minimumRiskScore,
            Integer maximumRiskScore,
            String cursor,
            int pageSize) {

        public RecoveryFlowSearchCriteria {
            status = optionalBounded(status, "status", 24);
            classification = optionalBounded(classification, "classification", 24);
            eventType = optionalBounded(eventType, "eventType", 32);
            cursor = optionalBounded(cursor, "cursor", 256);
            if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
            }
            if (minimumRiskScore != null && (minimumRiskScore < 0 || minimumRiskScore > 100)) {
                throw new IllegalArgumentException("minimumRiskScore must be between 0 and 100");
            }
            if (maximumRiskScore != null && (maximumRiskScore < 0 || maximumRiskScore > 100)) {
                throw new IllegalArgumentException("maximumRiskScore must be between 0 and 100");
            }
            if (minimumRiskScore != null && maximumRiskScore != null && minimumRiskScore > maximumRiskScore) {
                throw new IllegalArgumentException("minimumRiskScore must not exceed maximumRiskScore");
            }
            if (initiatedFrom != null && initiatedTo != null) {
                if (!initiatedFrom.isBefore(initiatedTo)) {
                    throw new IllegalArgumentException("initiatedFrom must be before initiatedTo");
                }
                if (Duration.between(initiatedFrom, initiatedTo).compareTo(MAX_TIME_WINDOW) > 0) {
                    throw new IllegalArgumentException(
                            "recovery search time window must not exceed " + MAX_TIME_WINDOW.toDays() + " days");
                }
            }
            if (eligibleBefore != null && eligibleAfter != null && !eligibleAfter.isBefore(eligibleBefore)) {
                throw new IllegalArgumentException("eligibleAfter must be before eligibleBefore");
            }
        }

        private static String optionalBounded(String value, String name, int maxLength) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim();
            if (normalized.isEmpty() || normalized.length() > maxLength) {
                throw new IllegalArgumentException(
                        name + " must contain between 1 and " + maxLength + " characters when provided");
            }
            return normalized;
        }
    }

    record RecoveryFlowSearchSummary(
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
            Instant eligibleAfter) {

        public RecoveryFlowSearchSummary {
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
        }
    }

    record RecoveryFlowSearchPage(
            List<RecoveryFlowSearchSummary> recoveries,
            String nextCursor,
            int pageSize,
            boolean hasMore) {

        public RecoveryFlowSearchPage {
            recoveries = List.copyOf(Objects.requireNonNull(recoveries, "recoveries must not be null"));
            if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
            }
            if (hasMore && (nextCursor == null || nextCursor.isBlank())) {
                throw new IllegalArgumentException("nextCursor is required when hasMore is true");
            }
            if (!hasMore) {
                nextCursor = null;
            }
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
