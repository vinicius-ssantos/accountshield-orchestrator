package io.github.viniciusssantos.accountshield.recovery.internal;

import io.github.viniciusssantos.accountshield.challenge.ChallengePlan;
import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.challenge.ChallengeUseRejectedException;
import io.github.viniciusssantos.accountshield.challenge.ConsumeChallengeCommand;
import io.github.viniciusssantos.accountshield.challenge.CreateChallengeCommand;
import io.github.viniciusssantos.accountshield.challenge.InvalidChallengeStateException;
import io.github.viniciusssantos.accountshield.recovery.ConfirmIdentityCommand;
import io.github.viniciusssantos.accountshield.recovery.InitiateRecoveryCommand;
import io.github.viniciusssantos.accountshield.recovery.InvalidRecoveryStateException;
import io.github.viniciusssantos.accountshield.recovery.RecoveryAuthorization;
import io.github.viniciusssantos.accountshield.recovery.RecoveryCompleted;
import io.github.viniciusssantos.accountshield.recovery.RecoveryEventType;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlow;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowConflictException;
import io.github.viniciusssantos.accountshield.recovery.RecoveryRiskClassification;
import io.github.viniciusssantos.accountshield.recovery.RecoveryReviewCommand;
import io.github.viniciusssantos.accountshield.recovery.RecoveryReviewDecision;
import io.github.viniciusssantos.accountshield.recovery.RecoveryService;
import io.github.viniciusssantos.accountshield.recovery.RecoveryStatus;
import io.github.viniciusssantos.accountshield.recovery.UnauthorizedRecoveryInitiationException;
import io.github.viniciusssantos.accountshield.recovery.UnknownRecoveryClassificationRuleException;
import io.github.viniciusssantos.accountshield.recovery.internal.persistence.RecoveryFlowEntity;
import io.github.viniciusssantos.accountshield.recovery.internal.persistence.RecoveryFlowRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RecoveryApplicationService implements RecoveryService {

    private static final String AUTHORIZATION_REJECTED_MESSAGE =
            "recovery authorization is invalid or unavailable";
    private static final Duration DELAY_PERIOD = Duration.ofMinutes(15);

    private final RecoveryFlowRepository recoveryFlowRepository;
    private final ChallengeService challengeService;
    private final RecoveryAuthorizationApplicationService authorizationService;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    RecoveryApplicationService(
            RecoveryFlowRepository recoveryFlowRepository,
            ChallengeService challengeService,
            RecoveryAuthorizationApplicationService authorizationService,
            @Qualifier("decisionClock") Clock clock,
            ApplicationEventPublisher eventPublisher) {
        this.recoveryFlowRepository = recoveryFlowRepository;
        this.challengeService = challengeService;
        this.authorizationService = authorizationService;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public RecoveryFlow initiate(InitiateRecoveryCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        return recoveryFlowRepository.findByAuthorizationId(command.authorizationId())
                .map(this::toDomain)
                .orElseGet(() -> initiateFromAuthorization(command.authorizationId()));
    }

    private RecoveryFlow initiateFromAuthorization(UUID authorizationId) {
        Instant now = clock.instant();
        RecoveryAuthorizationApplicationService.Consumption consumption = authorizationService
                .consume(authorizationId, now)
                .orElseThrow(this::authorizationRejected);

        if (!consumption.newlyConsumed()) {
            return recoveryFlowRepository.findByAuthorizationId(authorizationId)
                    .map(this::toDomain)
                    .orElseThrow(this::authorizationRejected);
        }

        RecoveryAuthorization authorization = consumption.authorization();
        UUID recoveryId = UUID.randomUUID();
        RecoveryRiskClassification classification = RecoveryClassificationRule.classify(authorization.riskScore());

        ChallengePlan identityChallenge = challengeService.create(new CreateChallengeCommand(
                authorization.accountReference(),
                ChallengeType.WEBAUTHN_SIMULATED,
                ChallengePurpose.RECOVERY_IDENTITY,
                recoveryId));

        RecoveryFlowEntity entity = new RecoveryFlowEntity(
                recoveryId,
                authorization.accountReference(),
                authorization.directive().eventType().name(),
                RecoveryStatus.VERIFYING_IDENTITY.name(),
                classification.name(),
                RecoveryClassificationRule.VERSION,
                identityChallenge.challengeId(),
                authorization.riskScore(),
                now,
                now,
                computeEligibleAfter(classification, now),
                null,
                authorization.protectionRequestId(),
                authorization.decisionId(),
                authorization.authorizationId());

        recoveryFlowRepository.saveAndFlush(entity);
        return toDomain(entity);
    }

    @Override
    @Transactional
    public RecoveryFlow confirmIdentity(ConfirmIdentityCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        RecoveryFlowEntity entity = loadOrThrow(command.recoveryId(), "confirm-identity");
        assertState(entity, RecoveryStatus.VERIFYING_IDENTITY, "confirm-identity");

        if (!command.challengeId().equals(entity.getIdentityChallengeId())) {
            throw new UnauthorizedRecoveryInitiationException(
                    "challenge does not belong to this recovery flow");
        }

        try {
            ChallengePlan challenge = challengeService.consume(new ConsumeChallengeCommand(
                    command.challengeId(),
                    entity.getAccountReference(),
                    ChallengePurpose.RECOVERY_IDENTITY,
                    command.recoveryId()));
            if (challenge.status() != ChallengeStatus.CONSUMED) {
                throw new InvalidChallengeStateException(
                        command.challengeId(), challenge.status());
            }
        } catch (ChallengeUseRejectedException exception) {
            throw new UnauthorizedRecoveryInitiationException(
                    "challenge cannot authorize this recovery flow");
        } catch (InvalidChallengeStateException exception) {
            entity.setStatus(RecoveryStatus.IDENTITY_FAILED.name());
            entity.setUpdatedAt(clock.instant());
            saveWithConflictCheck(entity);
            throw new InvalidRecoveryStateException(
                    command.recoveryId(), RecoveryStatus.IDENTITY_FAILED, "confirm-identity");
        }

        if (!RecoveryClassificationRule.isKnownVersion(entity.getClassificationRuleVersion())) {
            throw new UnknownRecoveryClassificationRuleException(
                    entity.getId(), entity.getClassificationRuleVersion());
        }

        RecoveryRiskClassification classification =
                RecoveryRiskClassification.valueOf(entity.getClassification());
        RecoveryStatus nextStatus = switch (classification) {
            case IMMEDIATE -> RecoveryStatus.IDENTITY_VERIFIED;
            case DELAYED -> RecoveryStatus.DELAYED;
            case MANUAL_REVIEW -> RecoveryStatus.MANUAL_REVIEW;
        };

        entity.setStatus(nextStatus.name());
        entity.setUpdatedAt(clock.instant());
        saveWithConflictCheck(entity);

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
        saveWithConflictCheck(entity);

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
        saveWithConflictCheck(entity);

        if (newStatus.equals(RecoveryStatus.COMPLETED.name())) {
            eventPublisher.publishEvent(new RecoveryCompleted(
                    command.recoveryId(),
                    entity.getAccountReference(),
                    entity.getEventType(),
                    now));
        }

        return toDomain(entity);
    }

    private void saveWithConflictCheck(RecoveryFlowEntity entity) {
        try {
            recoveryFlowRepository.saveAndFlush(entity);
        } catch (OptimisticLockingFailureException exception) {
            throw new RecoveryFlowConflictException(entity.getId(), exception);
        }
    }

    private UnauthorizedRecoveryInitiationException authorizationRejected() {
        return new UnauthorizedRecoveryInitiationException(AUTHORIZATION_REJECTED_MESSAGE);
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
                entity.getClassificationRuleVersion(),
                entity.getIdentityChallengeId(),
                entity.getInitiatedAt(),
                entity.getUpdatedAt(),
                entity.getEligibleAfter(),
                entity.getAuthorizationId(),
                entity.getProtectionRequestId(),
                entity.getOriginatingDecisionId());
    }
}
