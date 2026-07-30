package io.github.viniciusssantos.accountshield.recovery.internal;

import io.github.viniciusssantos.accountshield.challenge.ChallengeInvestigationQuery;
import io.github.viniciusssantos.accountshield.challenge.ChallengeInvestigationQuery.ChallengeInvestigationView;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowDetailQuery;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowDetailQuery.RecoveryChallengeSummary;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowDetailQuery.RecoveryFlowDetail;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowDetailQuery.SectionAvailability;
import io.github.viniciusssantos.accountshield.recovery.RecoveryStatus;
import io.github.viniciusssantos.accountshield.recovery.internal.persistence.RecoveryFlowEntity;
import io.github.viniciusssantos.accountshield.recovery.internal.persistence.RecoveryFlowRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RecoveryFlowDetailService implements RecoveryFlowDetailQuery {

    private final RecoveryFlowRepository repository;
    private final ChallengeInvestigationQuery challengeQuery;

    public RecoveryFlowDetailService(
            RecoveryFlowRepository repository,
            ChallengeInvestigationQuery challengeQuery) {
        this.repository = repository;
        this.challengeQuery = challengeQuery;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RecoveryFlowDetail> investigate(String recoveryReference) {
        UUID recoveryId = parseReference(recoveryReference);
        return repository.findById(recoveryId).map(this::compose);
    }

    private RecoveryFlowDetail compose(RecoveryFlowEntity flow) {
        List<RecoveryChallengeSummary> challenges = challengeQuery.findByContextId(flow.getId()).stream()
                .map(this::toChallengeSummary)
                .toList();

        SectionAvailability challengeSection = !challenges.isEmpty()
                ? SectionAvailability.AVAILABLE
                : flow.getIdentityChallengeId() != null
                        ? SectionAvailability.UNAVAILABLE
                        : SectionAvailability.NOT_APPLICABLE;

        boolean terminal = RecoveryStatus.valueOf(flow.getStatus()).isTerminal();

        return new RecoveryFlowDetail(
                flow.getId().toString(),
                maskSubject(flow.getAccountReference()),
                flow.getEventType(),
                flow.getStatus(),
                terminal,
                flow.getClassification(),
                flow.getClassificationRuleVersion(),
                flow.getRiskScore(),
                flow.getInitiatedAt(),
                flow.getUpdatedAt(),
                flow.getEligibleAfter(),
                terminal ? flow.getUpdatedAt() : null,
                flow.getReviewer(),
                maskReference(flow.getOriginatingDecisionId().toString()),
                maskReference(flow.getProtectionRequestId().toString()),
                challenges,
                challengeSection,
                challengeSection == SectionAvailability.UNAVAILABLE);
    }

    private RecoveryChallengeSummary toChallengeSummary(ChallengeInvestigationView view) {
        return new RecoveryChallengeSummary(
                view.reference().toString(),
                view.challengeType(),
                view.purpose(),
                view.status(),
                view.createdAt(),
                view.expiresAt(),
                view.consumedAt());
    }

    private UUID parseReference(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("recoveryReference must be a valid UUID");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("recoveryReference must be a valid UUID");
        }
    }

    private String maskSubject(String accountReference) {
        if (accountReference == null || accountReference.isBlank()) {
            return "masked-subject";
        }
        return "••••" + accountReference.substring(Math.max(0, accountReference.length() - 4));
    }

    private String maskReference(String reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        return "••••" + reference.substring(Math.max(0, reference.length() - 4));
    }
}
