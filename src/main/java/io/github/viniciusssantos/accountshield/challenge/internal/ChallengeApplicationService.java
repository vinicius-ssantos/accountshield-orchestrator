package io.github.viniciusssantos.accountshield.challenge.internal;

import io.github.viniciusssantos.accountshield.challenge.ChallengeCompleted;
import io.github.viniciusssantos.accountshield.challenge.ChallengePlan;
import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeResult;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.challenge.ChallengeUseRejectedException;
import io.github.viniciusssantos.accountshield.challenge.ChallengeVerificationCommand;
import io.github.viniciusssantos.accountshield.challenge.ConsumeChallengeCommand;
import io.github.viniciusssantos.accountshield.challenge.CreateChallengeCommand;
import io.github.viniciusssantos.accountshield.challenge.InvalidChallengeStateException;
import io.github.viniciusssantos.accountshield.challenge.internal.persistence.ChallengePlanEntity;
import io.github.viniciusssantos.accountshield.challenge.internal.persistence.ChallengePlanRepository;
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
class ChallengeApplicationService implements ChallengeService {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private final ChallengePlanRepository challengePlanRepository;
    private final SimulatedChallengeProvider challengeProvider;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    ChallengeApplicationService(
            ChallengePlanRepository challengePlanRepository,
            SimulatedChallengeProvider challengeProvider,
            @Qualifier("decisionClock") Clock clock,
            ApplicationEventPublisher eventPublisher) {
        this.challengePlanRepository = challengePlanRepository;
        this.challengeProvider = challengeProvider;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public ChallengePlan create(CreateChallengeCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        Instant now = clock.instant();
        UUID challengeId = UUID.randomUUID();
        String expectedCode = challengeProvider.generateCode();

        ChallengePlanEntity entity = new ChallengePlanEntity(
                challengeId,
                command.accountReference(),
                command.challengeType().name(),
                command.purpose().name(),
                command.contextId(),
                ChallengeStatus.CHALLENGED.name(),
                (short) DEFAULT_MAX_ATTEMPTS,
                (short) DEFAULT_MAX_ATTEMPTS,
                expectedCode,
                now,
                now.plus(DEFAULT_TTL),
                null);
        challengePlanRepository.save(entity);

        return toDomain(entity);
    }

    @Override
    @Transactional
    public ChallengeResult verify(ChallengeVerificationCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        ChallengePlanEntity plan = challengePlanRepository.findById(command.challengeId())
                .orElseThrow(ChallengeUseRejectedException::new);
        assertBinding(plan, command.purpose(), command.contextId());

        Instant now = clock.instant();
        ChallengeStatus status = ChallengeStatus.valueOf(plan.getStatus());

        if (status == ChallengeStatus.CONSUMED) {
            throw new ChallengeUseRejectedException();
        }
        if (status == ChallengeStatus.VERIFIED) {
            return alreadyVerified(plan);
        }
        if (status == ChallengeStatus.FAILED) {
            throw new InvalidChallengeStateException(command.challengeId(), ChallengeStatus.FAILED);
        }
        if (plan.getExpiresAt().isBefore(now)) {
            plan.setStatus(ChallengeStatus.EXPIRED.name());
            challengePlanRepository.save(plan);
            throw new InvalidChallengeStateException(command.challengeId(), ChallengeStatus.EXPIRED);
        }
        if (status != ChallengeStatus.CHALLENGED) {
            throw new InvalidChallengeStateException(command.challengeId(), status);
        }

        plan.setRemainingAttempts((short) (plan.getRemainingAttempts() - 1));

        if (challengeProvider.verifyCode(command.providedCode(), plan.getExpectedCode())) {
            plan.setStatus(ChallengeStatus.VERIFIED.name());
            challengePlanRepository.save(plan);
            eventPublisher.publishEvent(new ChallengeCompleted(
                    plan.getId(),
                    plan.getAccountReference(),
                    ChallengeType.valueOf(plan.getChallengeType()),
                    ChallengeStatus.VERIFIED,
                    now));
            return new ChallengeResult(
                    plan.getId(),
                    ChallengeStatus.VERIFIED,
                    true,
                    plan.getRemainingAttempts(),
                    plan.getExpiresAt());
        }

        if (plan.getRemainingAttempts() <= 0) {
            plan.setStatus(ChallengeStatus.FAILED.name());
            challengePlanRepository.save(plan);
            eventPublisher.publishEvent(new ChallengeCompleted(
                    plan.getId(),
                    plan.getAccountReference(),
                    ChallengeType.valueOf(plan.getChallengeType()),
                    ChallengeStatus.FAILED,
                    now));
            return new ChallengeResult(
                    plan.getId(),
                    ChallengeStatus.FAILED,
                    false,
                    0,
                    plan.getExpiresAt());
        }

        challengePlanRepository.save(plan);
        return new ChallengeResult(
                plan.getId(),
                ChallengeStatus.CHALLENGED,
                false,
                plan.getRemainingAttempts(),
                plan.getExpiresAt());
    }

    @Override
    @Transactional
    public ChallengePlan consume(ConsumeChallengeCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        ChallengePlanEntity plan = challengePlanRepository.findById(command.challengeId())
                .orElseThrow(ChallengeUseRejectedException::new);
        assertBinding(plan, command.purpose(), command.contextId());
        if (!plan.getAccountReference().equals(command.accountReference())) {
            throw new ChallengeUseRejectedException();
        }

        Instant now = clock.instant();
        ChallengeStatus status = ChallengeStatus.valueOf(plan.getStatus());
        if (status == ChallengeStatus.CONSUMED) {
            throw new ChallengeUseRejectedException();
        }
        if (plan.getExpiresAt().isBefore(now)) {
            if (status != ChallengeStatus.FAILED && status != ChallengeStatus.EXPIRED) {
                plan.setStatus(ChallengeStatus.EXPIRED.name());
                challengePlanRepository.saveAndFlush(plan);
            }
            throw new InvalidChallengeStateException(command.challengeId(), ChallengeStatus.EXPIRED);
        }
        if (status != ChallengeStatus.VERIFIED) {
            throw new InvalidChallengeStateException(command.challengeId(), status);
        }

        plan.setStatus(ChallengeStatus.CONSUMED.name());
        plan.setConsumedAt(now);
        try {
            challengePlanRepository.saveAndFlush(plan);
        } catch (OptimisticLockingFailureException exception) {
            throw new ChallengeUseRejectedException(exception);
        }
        return toDomain(plan);
    }

    private void assertBinding(
            ChallengePlanEntity plan,
            ChallengePurpose expectedPurpose,
            UUID expectedContextId) {
        if (!plan.getPurpose().equals(expectedPurpose.name())
                || !plan.getContextId().equals(expectedContextId)) {
            throw new ChallengeUseRejectedException();
        }
    }

    private ChallengeResult alreadyVerified(ChallengePlanEntity plan) {
        return new ChallengeResult(
                plan.getId(),
                ChallengeStatus.VERIFIED,
                true,
                plan.getRemainingAttempts(),
                plan.getExpiresAt());
    }

    private ChallengePlan toDomain(ChallengePlanEntity entity) {
        return new ChallengePlan(
                entity.getId(),
                entity.getAccountReference(),
                ChallengeType.valueOf(entity.getChallengeType()),
                ChallengePurpose.valueOf(entity.getPurpose()),
                entity.getContextId(),
                ChallengeStatus.valueOf(entity.getStatus()),
                entity.getMaxAttempts(),
                entity.getRemainingAttempts(),
                entity.getCreatedAt(),
                entity.getExpiresAt(),
                entity.getConsumedAt());
    }
}
