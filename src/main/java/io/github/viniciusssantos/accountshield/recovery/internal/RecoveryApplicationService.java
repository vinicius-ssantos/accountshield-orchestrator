package io.github.viniciusssantos.accountshield.recovery.internal;

import io.github.viniciusssantos.accountshield.challenge.ChallengePlan;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.recovery.ConfirmIdentityCommand;
import io.github.viniciusssantos.accountshield.recovery.InitiateRecoveryCommand;
import io.github.viniciusssantos.accountshield.recovery.InvalidRecoveryStateException;
import io.github.viniciusssantos.accountshield.recovery.RecoveryCompleted;
import io.github.viniciusssantos.accountshield.recovery.RecoveryEventType;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlow;
import io.github.viniciusssantos.accountshield.recovery.RecoveryRiskClassification;
import io.github.viniciusssantos.accountshield.recovery.RecoveryReviewCommand;
import io.github.viniciusssantos.accountshield.recovery.RecoveryReviewDecision;
import io.github.viniciusssantos.accountshield.recovery.RecoveryService;
import io.github.viniciusssantos.accountshield.recovery.RecoveryStatus;
import io.github.viniciusssantos.accountshield.recovery.internal.persistence.RecoveryFlowEntity;
import io.github.viniciusssantos.accountshield.recovery.internal.persistence.RecoveryFlowRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RecoveryApplicationService implements RecoveryService {

    private static final int IMMEDIATE_THRESHOLD = 30;
    private static final int DELAYED_THRESHOLD = 60;
    private static final Duration DELAY_PERIOD = Duration.ofMinutes(15);

    private final RecoveryFlowRepository recoveryFlowRepository;
    private final ChallengeService challengeService;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    RecoveryApplicationService(
            RecoveryFlowRepository recoveryFlowRepository,
            ChallengeService challengeService,
            @Qualifier("decisionClock") Clock clock,
            ApplicationEventPublisher eventPublisher) {
        this.recoveryFlowRepository = recoveryFlowRepository;
        this.challengeService = challengeService;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public RecoveryFlow initiate(InitiateRecoveryCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        Instant now = clock.instant();
        UUID recoveryId = UUID.randomUUID();
        RecoveryRiskClassification classification = classify(command.riskScore());

        ChallengePlan identityChallenge = challengeService.create(
                command.accountReference(),
                ChallengeType.WEBAUTHN_SIMULATED);

        RecoveryFlowEntity entity = new RecoveryFlowEntity(
                recoveryId,
                command.accountReference(),
                command.eventType().name(),
                RecoveryStatus.VERIFYING_IDENTITY.name(),
                classification.name(),
                identityChallenge.challengeId(),
                command.riskScore(),
                now,
                now,
                computeEligibleAfter(classification, now),
                null);

        recoveryFlowRepository.save(entity);

        return toDomain(entity);
    }

    @Override
    @Transactional
    public RecoveryFlow confirmIdentity(ConfirmIdentityCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        RecoveryFlowEntity entity = loadOrThrow(command.recoveryId(), "confirm-identity");
        assertState(entity, RecoveryStatus.VERIFYING_IDENTITY, "confirm-identity");

        ChallengePlan challenge = challengeService.verifyIdentityForRecovery(command.challengeId());
        if (challenge.status() != ChallengeStatus.VERIFIED) {
            entity.setStatus(RecoveryStatus.IDENTITY_FAILED.name());
            entity.setUpdatedAt(clock.instant());
            recoveryFlowRepository.save(entity);
            throw new InvalidRecoveryStateException(
                    command.recoveryId(), RecoveryStatus.IDENTITY_FAILED, "confirm-identity");
        }

        entity.setStatus(RecoveryStatus.IDENTITY_VERIFIED.name());
        entity.setUpdatedAt(clock.instant());
        recoveryFlowRepository.save(entity);

        return toDomain(entity);
    }

    @Override
    @Transactional
    public RecoveryFlow complete(UUID recoveryId) {
        Objects.requireNonNull(recoveryId, "recoveryId must not be null");

        RecoveryFlowEntity entity = loadOrThrow(recoveryId, "complete");
        RecoveryStatus current = RecoveryStatus.valueOf(entity.getStatus());

        if (current == RecoveryStatus.COMPLETED) {
            return toDomain(entity);
        }

        if (current == RecoveryStatus.MANUAL_REVIEW) {
            throw new InvalidRecoveryStateException(
                    recoveryId, RecoveryStatus.MANUAL_REVIEW, "complete");
        }

        if (current == RecoveryStatus.DELAYED) {
            Instant now = clock.instant();
            if (entity.getEligibleAfter() != null && now.isBefore(entity.getEligibleAfter())) {
                throw new InvalidRecoveryStateException(
                        recoveryId, RecoveryStatus.DELAYED, "complete-before-eligible");
            }
        } else if (current != RecoveryStatus.IDENTITY_VERIFIED) {
            throw new InvalidRecoveryStateException(recoveryId, current, "complete");
        }

        entity.setStatus(RecoveryStatus.COMPLETED.name());
        Instant completedAt = clock.instant();
        entity.setUpdatedAt(completedAt);
        recoveryFlowRepository.save(entity);

        eventPublisher.publishEvent(new RecoveryCompleted(
                recoveryId,
                entity.getAccountReference(),
                entity.getEventType(),
                completedAt));

        return toDomain(entity);
    }

    @Override
    @Transactional
    public RecoveryFlow review(RecoveryReviewCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        RecoveryFlowEntity entity = loadOrThrow(command.recoveryId(), "review");
        assertState(entity, RecoveryStatus.MANUAL_REVIEW, "review");

        Instant now = clock.instant();
        String newStatus = command.decision() == RecoveryReviewDecision.APPROVE
                ? RecoveryStatus.COMPLETED.name()
                : RecoveryStatus.REJECTED.name();

        entity.setStatus(newStatus);
        entity.setReviewer(command.reviewer());
        entity.setUpdatedAt(now);
        recoveryFlowRepository.save(entity);

        if (newStatus.equals(RecoveryStatus.COMPLETED.name())) {
            eventPublisher.publishEvent(new RecoveryCompleted(
                    command.recoveryId(),
                    entity.getAccountReference(),
                    entity.getEventType(),
                    now));
        }

        return toDomain(entity);
    }

    private RecoveryRiskClassification classify(int riskScore) {
        if (riskScore <= IMMEDIATE_THRESHOLD) {
            return RecoveryRiskClassification.IMMEDIATE;
        }
        if (riskScore <= DELAYED_THRESHOLD) {
            return RecoveryRiskClassification.DELAYED;
        }
        return RecoveryRiskClassification.MANUAL_REVIEW;
    }

    private Instant computeEligibleAfter(RecoveryRiskClassification classification, Instant now) {
        return classification == RecoveryRiskClassification.DELAYED
                ? now.plus(DELAY_PERIOD)
                : null;
    }

    private RecoveryFlowEntity loadOrThrow(UUID recoveryId, String action) {
        return recoveryFlowRepository.findById(recoveryId)
                .orElseThrow(() -> new InvalidRecoveryStateException(
                        recoveryId, RecoveryStatus.ABORTED, action));
    }

    private void assertState(RecoveryFlowEntity entity, RecoveryStatus expected, String action) {
        RecoveryStatus current = RecoveryStatus.valueOf(entity.getStatus());
        if (current != expected) {
            throw new InvalidRecoveryStateException(entity.getId(), current, action);
        }
    }

    private RecoveryFlow toDomain(RecoveryFlowEntity entity) {
        return new RecoveryFlow(
                entity.getId(),
                entity.getAccountReference(),
                RecoveryEventType.valueOf(entity.getEventType()),
                RecoveryStatus.valueOf(entity.getStatus()),
                RecoveryRiskClassification.valueOf(entity.getClassification()),
                entity.getIdentityChallengeId(),
                entity.getInitiatedAt(),
                entity.getUpdatedAt(),
                entity.getEligibleAfter());
    }
}
