package io.github.viniciusssantos.accountshield.audit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Privacy-minimized read port for the security-operations console.
 *
 * <p>This contract deliberately does not expose {@link DecisionTraceView}, normalized context,
 * account references, request fingerprints, reason details, provider payloads, or persistence
 * entities.</p>
 */
public interface DecisionInvestigationQuery {

    int DEFAULT_PAGE_SIZE = 25;
    int MAX_PAGE_SIZE = 100;
    Duration MAX_TIME_WINDOW = Duration.ofDays(31);

    DecisionInvestigationPage search(DecisionInvestigationCriteria criteria);

    Optional<DecisionInvestigationDetail> investigate(String decisionReference);

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

    enum SectionAvailability {
        AVAILABLE,
        NOT_APPLICABLE,
        UNAVAILABLE
    }

    record DecisionReasonSummary(String code, int contribution, int ordinal) {
        public DecisionReasonSummary {
            code = requireText(code, "code");
            if (contribution < -100 || contribution > 100) {
                throw new IllegalArgumentException("contribution must be between -100 and 100");
            }
            if (ordinal < 0) {
                throw new IllegalArgumentException("ordinal must not be negative");
            }
        }
    }

    record SignalProvenanceSummary(
            String provider,
            Instant observedAt,
            String confidence,
            String schemaVersion,
            String state,
            boolean simulated,
            boolean integrityAvailable) {

        public SignalProvenanceSummary {
            state = requireText(state, "state");
        }
    }

    record PolicyProvenanceSummary(
            String policyKey,
            String policyVersion,
            String routingReason,
            Integer rolloutCohortBucket,
            String rolloutCandidateVersion,
            Boolean rolloutCandidateSelected) {

        public PolicyProvenanceSummary {
            policyKey = requireText(policyKey, "policyKey");
            policyVersion = requireText(policyVersion, "policyVersion");
            routingReason = requireText(routingReason, "routingReason");
        }
    }

    record ExecutionProvenanceSummary(
            String algorithmVersion,
            String normalizedInputSchemaVersion,
            String reasonCatalogVersion,
            String decisionEngineVersion,
            String applicationCommitSha,
            boolean canonicalInputHashAvailable,
            boolean auditRecordHashAvailable) {

        public ExecutionProvenanceSummary {
            algorithmVersion = requireText(algorithmVersion, "algorithmVersion");
        }
    }

    record ChallengeSummary(
            String reference,
            String challengeType,
            String purpose,
            String status,
            Instant createdAt,
            Instant expiresAt,
            Instant consumedAt) {

        public ChallengeSummary {
            reference = requireText(reference, "reference");
            challengeType = requireText(challengeType, "challengeType");
            purpose = requireText(purpose, "purpose");
            status = requireText(status, "status");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        }
    }

    record RecoverySummary(
            String reference,
            String directive,
            String status,
            Instant issuedAt,
            Instant expiresAt,
            Instant consumedAt) {

        public RecoverySummary {
            reference = requireText(reference, "reference");
            directive = requireText(directive, "directive");
            status = requireText(status, "status");
            Objects.requireNonNull(issuedAt, "issuedAt must not be null");
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        }
    }

    record OutboxSummary(
            String reference,
            String eventType,
            String status,
            Instant occurredAt,
            Instant publishedAt,
            Instant deadLetteredAt,
            int attemptCount) {

        public OutboxSummary {
            reference = requireText(reference, "reference");
            eventType = requireText(eventType, "eventType");
            status = requireText(status, "status");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            if (attemptCount < 0) {
                throw new IllegalArgumentException("attemptCount must not be negative");
            }
        }
    }

    record DecisionTimelineEntry(
            String reference,
            String kind,
            String status,
            Instant occurredAt) {

        public DecisionTimelineEntry {
            reference = requireText(reference, "reference");
            kind = requireText(kind, "kind");
            status = requireText(status, "status");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        }
    }

    record InvestigationSections(
            SectionAvailability challenge,
            SectionAvailability recovery,
            SectionAvailability outbox) {

        public InvestigationSections {
            Objects.requireNonNull(challenge, "challenge must not be null");
            Objects.requireNonNull(recovery, "recovery must not be null");
            Objects.requireNonNull(outbox, "outbox must not be null");
        }
    }

    record DecisionInvestigationDetail(
            DecisionInvestigationSummary decision,
            String maskedSubjectReference,
            List<DecisionReasonSummary> reasons,
            SignalProvenanceSummary signalProvenance,
            PolicyProvenanceSummary policyProvenance,
            ExecutionProvenanceSummary executionProvenance,
            List<ChallengeSummary> challenges,
            RecoverySummary recovery,
            List<OutboxSummary> outboxEvents,
            List<DecisionTimelineEntry> timeline,
            InvestigationSections sections,
            boolean partial) {

        public DecisionInvestigationDetail {
            Objects.requireNonNull(decision, "decision must not be null");
            maskedSubjectReference = requireText(maskedSubjectReference, "maskedSubjectReference");
            reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons must not be null"));
            Objects.requireNonNull(signalProvenance, "signalProvenance must not be null");
            Objects.requireNonNull(policyProvenance, "policyProvenance must not be null");
            Objects.requireNonNull(executionProvenance, "executionProvenance must not be null");
            challenges = List.copyOf(Objects.requireNonNull(challenges, "challenges must not be null"));
            outboxEvents = List.copyOf(Objects.requireNonNull(outboxEvents, "outboxEvents must not be null"));
            timeline = List.copyOf(Objects.requireNonNull(timeline, "timeline must not be null"));
            Objects.requireNonNull(sections, "sections must not be null");
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
