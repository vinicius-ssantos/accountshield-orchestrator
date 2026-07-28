package io.github.viniciusssantos.accountshield.audit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Privacy-minimized read port for the security-operations console.
 *
 * <p>This contract deliberately does not expose {@link DecisionTraceView}, normalized context,
 * account references, request fingerprints, reason details, or persistence entities.</p>
 */
public interface DecisionInvestigationQuery {

    int DEFAULT_PAGE_SIZE = 25;
    int MAX_PAGE_SIZE = 100;
    Duration MAX_TIME_WINDOW = Duration.ofDays(31);

    DecisionInvestigationPage search(DecisionInvestigationCriteria criteria);

    record DecisionInvestigationCriteria(
            String correlationId,
            String eventType,
            String outcome,
            String riskBand,
            String policyVersion,
            Instant decidedFrom,
            Instant decidedTo,
            String cursor,
            int pageSize) {

        public DecisionInvestigationCriteria {
            correlationId = optionalBounded(correlationId, "correlationId", 128);
            eventType = optionalBounded(eventType, "eventType", 64);
            outcome = optionalBounded(outcome, "outcome", 32);
            riskBand = optionalBounded(riskBand, "riskBand", 16);
            policyVersion = optionalBounded(policyVersion, "policyVersion", 40);
            cursor = optionalBounded(cursor, "cursor", 256);
            if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException(
                        "pageSize must be between 1 and " + MAX_PAGE_SIZE);
            }
            if (decidedFrom != null && decidedTo != null) {
                if (!decidedFrom.isBefore(decidedTo)) {
                    throw new IllegalArgumentException("decidedFrom must be before decidedTo");
                }
                if (Duration.between(decidedFrom, decidedTo).compareTo(MAX_TIME_WINDOW) > 0) {
                    throw new IllegalArgumentException(
                            "decision search time window must not exceed " + MAX_TIME_WINDOW.toDays() + " days");
                }
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

    record DecisionInvestigationSummary(
            String decisionReference,
            String correlationId,
            String eventType,
            String outcome,
            int riskScore,
            String riskBand,
            String policyKey,
            String policyVersion,
            Instant decidedAt,
            boolean degraded,
            boolean simulated,
            boolean provenanceAvailable) {

        public DecisionInvestigationSummary {
            decisionReference = requireText(decisionReference, "decisionReference");
            correlationId = requireText(correlationId, "correlationId");
            eventType = requireText(eventType, "eventType");
            outcome = requireText(outcome, "outcome");
            if (riskScore < 0 || riskScore > 100) {
                throw new IllegalArgumentException("riskScore must be between 0 and 100");
            }
            riskBand = requireText(riskBand, "riskBand");
            policyKey = requireText(policyKey, "policyKey");
            policyVersion = requireText(policyVersion, "policyVersion");
            Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name + " must not be null");
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }

    record DecisionInvestigationPage(
            List<DecisionInvestigationSummary> decisions,
            String nextCursor,
            int pageSize,
            boolean hasMore) {

        public DecisionInvestigationPage {
            decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions must not be null"));
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
}
