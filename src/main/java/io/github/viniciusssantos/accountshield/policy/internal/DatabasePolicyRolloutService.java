package io.github.viniciusssantos.accountshield.policy.internal;

import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.challenge.ConsumeChallengeCommand;
import io.github.viniciusssantos.accountshield.challenge.CreateChallengeCommand;
import io.github.viniciusssantos.accountshield.policy.PolicyRollout;
import io.github.viniciusssantos.accountshield.policy.PolicyRolloutNotFoundException;
import io.github.viniciusssantos.accountshield.policy.PolicyRolloutService;
import io.github.viniciusssantos.accountshield.policy.PolicyRolloutStatus;
import io.github.viniciusssantos.accountshield.policy.PolicyStatus;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionNotFoundException;
import io.github.viniciusssantos.accountshield.policy.PrivilegedPolicyActionAttempted;
import io.github.viniciusssantos.accountshield.policy.RolloutAlreadyActiveException;
import io.github.viniciusssantos.accountshield.policy.RolloutCandidateNotApprovedException;
import io.github.viniciusssantos.accountshield.policy.StepUpChallenge;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyRolloutEntity;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyRolloutRepository;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionEntity;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DatabasePolicyRolloutService implements PolicyRolloutService {

    private static final String ACTIVE_ROLLOUT = PolicyRolloutStatus.ACTIVE.name();
    private static final String ACTION_START_ROLLOUT = "START_ROLLOUT";
    private static final String ACTION_UPDATE_ROLLOUT = "UPDATE_ROLLOUT";

    private final PolicyRolloutRepository rolloutRepository;
    private final PolicyVersionRepository versionRepository;
    private final ChallengeService challengeService;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;
    private final PolicySimulatedStepUpCodeCapture simulatedStepUpCodeCapture;
    private final boolean simulationEnabled;

    DatabasePolicyRolloutService(
            PolicyRolloutRepository rolloutRepository,
            PolicyVersionRepository versionRepository,
            ChallengeService challengeService,
            @Qualifier("decisionClock") Clock clock,
            ApplicationEventPublisher eventPublisher,
            PolicySimulatedStepUpCodeCapture simulatedStepUpCodeCapture,
            @Value("${accountshield.challenge.simulation-enabled:true}") boolean simulationEnabled) {
        this.rolloutRepository = rolloutRepository;
        this.versionRepository = versionRepository;
        this.challengeService = challengeService;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
        this.simulatedStepUpCodeCapture = simulatedStepUpCodeCapture;
        this.simulationEnabled = simulationEnabled;
    }

    @Override
    @Transactional
    public StepUpChallenge requestRolloutStepUp(String policyKey, String candidateVersion, String actor) {
        validateActor(actor);
        return issueStepUpChallenge(policyKey, candidateVersion, ACTION_START_ROLLOUT, actor);
    }

    @Override
    @Transactional
    public PolicyRollout startRollout(
            String policyKey, String candidateVersion, int rolloutPercentage, UUID stepUpChallengeId, String actor) {
        validateActor(actor);
        validatePercentage(rolloutPercentage);
        consumeStepUp(policyKey, candidateVersion, ACTION_START_ROLLOUT, stepUpChallengeId, actor);

        PolicyVersionEntity candidate = versionRepository.findByPolicyKeyAndVersion(policyKey, candidateVersion)
                .orElseThrow(() -> new PolicyVersionNotFoundException(policyKey, candidateVersion));
        if (!PolicyStatus.APPROVED.name().equals(candidate.getStatus())) {
            throw new RolloutCandidateNotApprovedException(policyKey, candidateVersion);
        }
        if (rolloutRepository.findByPolicyKeyAndStatus(policyKey, ACTIVE_ROLLOUT).isPresent()) {
            throw new RolloutAlreadyActiveException(policyKey);
        }

        Instant now = Instant.now(clock);
        PolicyRolloutEntity entity = new PolicyRolloutEntity(
                UUID.randomUUID(),
                policyKey,
                candidateVersion,
                (short) rolloutPercentage,
                ACTIVE_ROLLOUT,
                now,
                actor,
                now);
        rolloutRepository.save(entity);
        return toRollout(entity);
    }

    @Override
    @Transactional
    public StepUpChallenge requestPercentageUpdateStepUp(String policyKey, String actor) {
        validateActor(actor);
        PolicyRolloutEntity entity = requireActiveRolloutEntity(policyKey);
        return issueStepUpChallenge(policyKey, entity.getCandidateVersion(), ACTION_UPDATE_ROLLOUT, actor);
    }

    @Override
    @Transactional
    public PolicyRollout updatePercentage(
            String policyKey, int rolloutPercentage, UUID stepUpChallengeId, String actor) {
        validateActor(actor);
        validatePercentage(rolloutPercentage);
        PolicyRolloutEntity entity = requireActiveRolloutEntity(policyKey);
        consumeStepUp(policyKey, entity.getCandidateVersion(), ACTION_UPDATE_ROLLOUT, stepUpChallengeId, actor);

        entity.updatePercentage((short) rolloutPercentage, Instant.now(clock));
        return toRollout(entity);
    }

    @Override
    @Transactional
    public PolicyRollout rollback(String policyKey, String actor) {
        validateActor(actor);
        PolicyRolloutEntity entity = requireActiveRolloutEntity(policyKey);
        entity.rollback(actor, Instant.now(clock));
        return toRollout(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PolicyRollout> findActiveRollout(String policyKey) {
        return rolloutRepository.findByPolicyKeyAndStatus(policyKey, ACTIVE_ROLLOUT)
                .filter(entity -> versionRepository.findByPolicyKeyAndVersion(policyKey, entity.getCandidateVersion())
                        .map(version -> PolicyStatus.APPROVED.name().equals(version.getStatus()))
                        .orElse(false))
                .map(this::toRollout);
    }

    private PolicyRolloutEntity requireActiveRolloutEntity(String policyKey) {
        return rolloutRepository.findByPolicyKeyAndStatus(policyKey, ACTIVE_ROLLOUT)
                .orElseThrow(() -> new PolicyRolloutNotFoundException(policyKey));
    }

    private void consumeStepUp(
            String policyKey, String candidateVersion, String action, UUID stepUpChallengeId, String actor) {
        try {
            challengeService.consume(new ConsumeChallengeCommand(
                    stepUpChallengeId, actor, ChallengePurpose.PRIVILEGED_OPERATION,
                    stepUpContextId(policyKey, candidateVersion, action)));
            eventPublisher.publishEvent(
                    new PrivilegedPolicyActionAttempted(policyKey, candidateVersion, action, actor, true));
        } catch (RuntimeException exception) {
            eventPublisher.publishEvent(
                    new PrivilegedPolicyActionAttempted(policyKey, candidateVersion, action, actor, false));
            throw exception;
        }
    }

    private StepUpChallenge issueStepUpChallenge(
            String policyKey, String candidateVersion, String action, String actor) {
        UUID contextId = stepUpContextId(policyKey, candidateVersion, action);
        UUID challengeId = challengeService.create(new CreateChallengeCommand(
                actor,
                ChallengeType.TOTP_SIMULATED,
                ChallengePurpose.PRIVILEGED_OPERATION,
                contextId)).challengeId();
        String simulatedCode = simulationEnabled ? simulatedStepUpCodeCapture.consume(challengeId) : null;
        return new StepUpChallenge(challengeId, simulatedCode, contextId);
    }

    private UUID stepUpContextId(String policyKey, String candidateVersion, String action) {
        return UUID.nameUUIDFromBytes(
                ("policy-rollout:" + action + ":" + policyKey + ":" + candidateVersion)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private void validateActor(String actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        if (actor.isBlank() || actor.length() > 200) {
            throw new IllegalArgumentException("actor must contain between 1 and 200 characters");
        }
    }

    private void validatePercentage(int rolloutPercentage) {
        if (rolloutPercentage < 0 || rolloutPercentage > 100) {
            throw new IllegalArgumentException("rolloutPercentage must be between 0 and 100");
        }
    }

    private PolicyRollout toRollout(PolicyRolloutEntity entity) {
        return new PolicyRollout(
                entity.getId(),
                entity.getPolicyKey(),
                entity.getCandidateVersion(),
                entity.getRolloutPercentage(),
                PolicyRolloutStatus.valueOf(entity.getStatus()),
                entity.getStartedAt(),
                entity.getStartedBy(),
                entity.getUpdatedAt(),
                entity.getRolledBackAt(),
                entity.getRolledBackBy());
    }
}
