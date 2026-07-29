package io.github.viniciusssantos.accountshield.investigation.internal;

import io.github.viniciusssantos.accountshield.audit.DecisionEvidenceQuery;
import io.github.viniciusssantos.accountshield.audit.DecisionEvidenceQuery.DecisionEvidence;
import io.github.viniciusssantos.accountshield.challenge.ChallengeInvestigationQuery;
import io.github.viniciusssantos.accountshield.investigation.DecisionTimelineQuery;
import io.github.viniciusssantos.accountshield.investigation.DecisionTimelineQuery.ChallengeSummary;
import io.github.viniciusssantos.accountshield.investigation.DecisionTimelineQuery.DecisionTimeline;
import io.github.viniciusssantos.accountshield.investigation.DecisionTimelineQuery.InvestigationSections;
import io.github.viniciusssantos.accountshield.investigation.DecisionTimelineQuery.OutboxSummary;
import io.github.viniciusssantos.accountshield.investigation.DecisionTimelineQuery.RecoverySummary;
import io.github.viniciusssantos.accountshield.investigation.DecisionTimelineQuery.SectionAvailability;
import io.github.viniciusssantos.accountshield.investigation.DecisionTimelineQuery.TimelineEntry;
import io.github.viniciusssantos.accountshield.outbox.OutboxInvestigationQuery;
import io.github.viniciusssantos.accountshield.recovery.RecoveryInvestigationQuery;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DecisionTimelineService implements DecisionTimelineQuery {

    private final DecisionEvidenceQuery evidenceQuery;
    private final ChallengeInvestigationQuery challengeQuery;
    private final RecoveryInvestigationQuery recoveryQuery;
    private final OutboxInvestigationQuery outboxQuery;

    public DecisionTimelineService(
            DecisionEvidenceQuery evidenceQuery,
            ChallengeInvestigationQuery challengeQuery,
            RecoveryInvestigationQuery recoveryQuery,
            OutboxInvestigationQuery outboxQuery) {
        this.evidenceQuery = evidenceQuery;
        this.challengeQuery = challengeQuery;
        this.recoveryQuery = recoveryQuery;
        this.outboxQuery = outboxQuery;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DecisionTimeline> investigate(String decisionReference) {
        return evidenceQuery.findByDecisionReference(decisionReference).map(this::compose);
    }

    private DecisionTimeline compose(DecisionEvidence evidence) {
        List<ChallengeSummary> challenges = challengeQuery
                .findByContextId(evidence.protectionRequestId()).stream()
                .map(view -> new ChallengeSummary(
                        view.reference().toString(),
                        view.challengeType(),
                        view.purpose(),
                        view.status(),
                        view.createdAt(),
                        view.expiresAt(),
                        view.consumedAt()))
                .toList();
        RecoverySummary recovery = recoveryQuery
                .findByDecisionId(java.util.UUID.fromString(evidence.decision().decisionReference()))
                .map(view -> new RecoverySummary(
                        view.reference().toString(),
                        view.directive(),
                        view.status(),
                        view.issuedAt(),
                        view.expiresAt(),
                        view.consumedAt()))
                .orElse(null);
        List<OutboxSummary> outboxEvents = outboxQuery
                .findByDecisionReference(evidence.decision().decisionReference()).stream()
                .map(view -> new OutboxSummary(
                        view.reference(),
                        view.eventType(),
                        view.status(),
                        view.occurredAt(),
                        view.publishedAt(),
                        view.deadLetteredAt(),
                        view.attemptCount()))
                .toList();

        InvestigationSections sections = sections(
                evidence.decision().outcome(), challenges, recovery, outboxEvents);
        List<TimelineEntry> timeline = timeline(evidence, challenges, recovery, outboxEvents);
        boolean partial = evidence.partial()
                || sections.challenge() == SectionAvailability.UNAVAILABLE
                || sections.recovery() == SectionAvailability.UNAVAILABLE
                || sections.outbox() == SectionAvailability.UNAVAILABLE;

        return new DecisionTimeline(
                evidence,
                challenges,
                recovery,
                outboxEvents,
                timeline,
                sections,
                partial);
    }

    private InvestigationSections sections(
            String outcome,
            List<ChallengeSummary> challenges,
            RecoverySummary recovery,
            List<OutboxSummary> outboxEvents) {
        SectionAvailability challenge = !challenges.isEmpty()
                ? SectionAvailability.AVAILABLE
                : "REQUIRE_STEP_UP".equals(outcome)
                        ? SectionAvailability.UNAVAILABLE
                        : SectionAvailability.NOT_APPLICABLE;
        SectionAvailability recoveryAvailability = recovery != null
                ? SectionAvailability.AVAILABLE
                : "START_RECOVERY".equals(outcome)
                        ? SectionAvailability.UNAVAILABLE
                        : SectionAvailability.NOT_APPLICABLE;
        SectionAvailability outbox = outboxEvents.isEmpty()
                ? SectionAvailability.UNAVAILABLE
                : SectionAvailability.AVAILABLE;
        return new InvestigationSections(challenge, recoveryAvailability, outbox);
    }

    private List<TimelineEntry> timeline(
            DecisionEvidence evidence,
            List<ChallengeSummary> challenges,
            RecoverySummary recovery,
            List<OutboxSummary> outboxEvents) {
        List<TimelineEntry> entries = new ArrayList<>();
        entries.add(new TimelineEntry(
                evidence.protectionRequestId().toString(),
                "REQUEST_RECEIVED",
                "RECEIVED",
                evidence.requestedAt()));
        entries.add(new TimelineEntry(
                evidence.decision().decisionReference(),
                "DECISION_RECORDED",
                evidence.decision().outcome(),
                evidence.decision().decidedAt()));

        for (ChallengeSummary challenge : challenges) {
            entries.add(new TimelineEntry(
                    challenge.reference(),
                    "CHALLENGE_CREATED",
                    "CREATED",
                    challenge.createdAt()));
            if (challenge.consumedAt() != null) {
                entries.add(new TimelineEntry(
                        challenge.reference(),
                        "CHALLENGE_CONSUMED",
                        "CONSUMED",
                        challenge.consumedAt()));
            }
        }
        if (recovery != null) {
            entries.add(new TimelineEntry(
                    recovery.reference(),
                    "RECOVERY_AUTHORIZATION_ISSUED",
                    "ISSUED",
                    recovery.issuedAt()));
            if (recovery.consumedAt() != null) {
                entries.add(new TimelineEntry(
                        recovery.reference(),
                        "RECOVERY_AUTHORIZATION_CONSUMED",
                        "CONSUMED",
                        recovery.consumedAt()));
            }
        }
        for (OutboxSummary event : outboxEvents) {
            entries.add(new TimelineEntry(
                    event.reference(),
                    "OUTBOX_EVENT_RECORDED",
                    "RECORDED",
                    event.occurredAt()));
            if (event.publishedAt() != null) {
                entries.add(new TimelineEntry(
                        event.reference(),
                        "OUTBOX_EVENT_PUBLISHED",
                        "PUBLISHED",
                        event.publishedAt()));
            }
            if (event.deadLetteredAt() != null) {
                entries.add(new TimelineEntry(
                        event.reference(),
                        "OUTBOX_EVENT_DEAD_LETTERED",
                        "DEAD_LETTERED",
                        event.deadLetteredAt()));
            }
        }

        entries.sort(Comparator
                .comparing(TimelineEntry::occurredAt)
                .thenComparingInt(entry -> priority(entry.kind()))
                .thenComparing(TimelineEntry::reference));
        return List.copyOf(entries);
    }

    private int priority(String kind) {
        return switch (kind) {
            case "REQUEST_RECEIVED" -> 10;
            case "CHALLENGE_CREATED" -> 20;
            case "DECISION_RECORDED" -> 30;
            case "RECOVERY_AUTHORIZATION_ISSUED" -> 40;
            case "OUTBOX_EVENT_RECORDED" -> 50;
            case "CHALLENGE_CONSUMED" -> 60;
            case "RECOVERY_AUTHORIZATION_CONSUMED" -> 70;
            case "OUTBOX_EVENT_PUBLISHED" -> 80;
            case "OUTBOX_EVENT_DEAD_LETTERED" -> 90;
            default -> 100;
        };
    }
}
