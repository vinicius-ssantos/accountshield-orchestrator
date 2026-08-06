package io.github.viniciusssantos.accountshield.investigation;

import io.github.viniciusssantos.accountshield.audit.DecisionEvidenceQuery.DecisionEvidence;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Composes minimized module-owned projections into one deterministic operator timeline. */
public interface DecisionTimelineQuery {

    Optional<DecisionTimeline> investigate(String decisionReference);

    enum SectionAvailability {
        AVAILABLE,
        NOT_APPLICABLE,
        UNAVAILABLE
    }

    record ChallengeSummary(
            String reference,
            String challengeType,
            String purpose,
            String status,
            Instant createdAt,
            Instant expiresAt,
            Instant consumedAt) {
    }

    record RecoverySummary(
            String reference,
            String directive,
            String status,
            Instant issuedAt,
            Instant expiresAt,
            Instant consumedAt) {
    }

    record OutboxSummary(
            String reference,
            String eventType,
            String status,
            Instant occurredAt,
            Instant publishedAt,
            Instant deadLetteredAt,
            int attemptCount) {
    }

    record TimelineEntry(
            String reference,
            String kind,
            String status,
            Instant occurredAt) {

        public TimelineEntry {
            requireText(reference, "reference");
            requireText(kind, "kind");
            requireText(status, "status");
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

    record DecisionTimeline(
            DecisionEvidence evidence,
            List<ChallengeSummary> challenges,
            RecoverySummary recovery,
            List<OutboxSummary> outboxEvents,
            List<TimelineEntry> timeline,
            InvestigationSections sections,
            boolean partial) {

        public DecisionTimeline {
            Objects.requireNonNull(evidence, "evidence must not be null");
            challenges = List.copyOf(Objects.requireNonNull(challenges, "challenges must not be null"));
            outboxEvents = List.copyOf(Objects.requireNonNull(outboxEvents, "outboxEvents must not be null"));
            timeline = List.copyOf(Objects.requireNonNull(timeline, "timeline must not be null"));
            Objects.requireNonNull(sections, "sections must not be null");
        }
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
