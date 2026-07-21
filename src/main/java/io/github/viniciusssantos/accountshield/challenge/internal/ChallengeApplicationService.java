package io.github.viniciusssantos.accountshield.challenge.internal;

import io.github.viniciusssantos.accountshield.challenge.ChallengePlan;
import io.github.viniciusssantos.accountshield.challenge.ChallengeResult;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.challenge.ChallengeVerificationCommand;
import io.github.viniciusssantos.accountshield.challenge.InvalidChallengeStateException;
import io.github.viniciusssantos.accountshield.challenge.internal.persistence.ChallengePlanEntity;
import io.github.viniciusssantos.accountshield.challenge.internal.persistence.ChallengePlanRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChallengeApplicationService implements ChallengeService {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private final ChallengePlanRepository challengePlanRepository;
    private final SimulatedChallengeProvider challengeProvider;
    private final Clock clock;

    ChallengeApplicationService(
            ChallengePlanRepository challengePlanRepository,
            SimulatedChallengeProvider challengeProvider,
            @Qualifier("decisionClock") Clock clock) {
        this.challengePlanRepository = challengePlanRepository;
        this.challengeProvider = challengeProvider;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ChallengePlan create(String accountReference, ChallengeType challengeType) {
        Objects.requireNonNull(accountReference, "accountReference must not be null");
        Objects.requireNonNull(challengeType, "challengeType must not be null");

        Instant now = clock.instant();
        UUID challengeId = UUID.randomUUID();
        String expectedCode = challengeProvider.generateCode();

        challengePlanRepository.save(new ChallengePlanEntity(
                challengeId,
                accountReference,
                challengeType.name(),
                ChallengeStatus.CHALLENGED.name(),
                (short) DEFAULT_MAX_ATTEMPTS,
                (short) DEFAULT_MAX_ATTEMPTS,
                expectedCode,
                now,
                now.plus(DEFAULT_TTL)));

        return new ChallengePlan(
                challengeId,
                accountReference,
                challengeType,
                ChallengeStatus.CHALLENGED,
                DEFAULT_MAX_ATTEMPTS,
                DEFAULT_MAX_ATTEMPTS,
                now,
                now.plus(DEFAULT_TTL));
    }

    @Override
    @Transactional
    public ChallengeResult verify(ChallengeVerificationCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        ChallengePlanEntity plan = challengePlanRepository.findById(command.challengeId())
                .orElseThrow(() -> new InvalidChallengeStateException(
                        command.challengeId(), ChallengeStatus.EXPIRED));

        Instant now = clock.instant();

        if (plan.getStatus().equals(ChallengeStatus.VERIFIED.name())) {
            return alreadyVerified(plan);
        }

        if (plan.getStatus().equals(ChallengeStatus.FAILED.name())) {
            throw new InvalidChallengeStateException(command.challengeId(), ChallengeStatus.FAILED);
        }

        if (plan.getExpiresAt().isBefore(now)) {
            plan.setStatus(ChallengeStatus.EXPIRED.name());
            challengePlanRepository.save(plan);
            throw new InvalidChallengeStateException(command.challengeId(), ChallengeStatus.EXPIRED);
        }

        if (!plan.getStatus().equals(ChallengeStatus.CHALLENGED.name())) {
            throw new InvalidChallengeStateException(command.challengeId(), ChallengeStatus.valueOf(plan.getStatus()));
        }

        plan.setRemainingAttempts((short) (plan.getRemainingAttempts() - 1));

        if (challengeProvider.verifyCode(command.providedCode(), plan.getExpectedCode())) {
            plan.setStatus(ChallengeStatus.VERIFIED.name());
            challengePlanRepository.save(plan);
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

    private ChallengeResult alreadyVerified(ChallengePlanEntity plan) {
        return new ChallengeResult(
                plan.getId(),
                ChallengeStatus.VERIFIED,
                true,
                plan.getRemainingAttempts(),
                plan.getExpiresAt());
    }
}
