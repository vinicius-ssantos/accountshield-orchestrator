package io.github.viniciusssantos.accountshield.policy.internal;

import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.challenge.ConsumeChallengeCommand;
import io.github.viniciusssantos.accountshield.challenge.CreateChallengeCommand;
import io.github.viniciusssantos.accountshield.policy.CreatePolicyCommand;
import io.github.viniciusssantos.accountshield.policy.DuplicatePolicyVersionException;
import io.github.viniciusssantos.accountshield.policy.PendingPolicyVersionExistsException;
import io.github.viniciusssantos.accountshield.policy.PolicyActivated;
import io.github.viniciusssantos.accountshield.policy.PolicyAnalysisFailedException;
import io.github.viniciusssantos.accountshield.policy.PolicyAnalysisResult;
import io.github.viniciusssantos.accountshield.policy.PolicyAnalyzer;
import io.github.viniciusssantos.accountshield.policy.PolicyDefinition;
import io.github.viniciusssantos.accountshield.policy.PolicyGovernance;
import io.github.viniciusssantos.accountshield.policy.PolicyLifecycleService;
import io.github.viniciusssantos.accountshield.policy.PolicyStatus;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionNotFoundException;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionSummary;
import io.github.viniciusssantos.accountshield.policy.PrivilegedPolicyActionAttempted;
import io.github.viniciusssantos.accountshield.policy.SelfApprovalNotAllowedException;
import io.github.viniciusssantos.accountshield.policy.StepUpChallenge;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionEntity;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class PolicyLifecycleApplicationService implements PolicyLifecycleService {

    private static final String ACTIVE = PolicyStatus.ACTIVE.name();
    private static final List<String> PENDING_STATUSES = List.of(
            PolicyStatus.DRAFT.name(),
            PolicyStatus.VALIDATED.name(),
            PolicyStatus.APPROVED.name());
    private static final String ACTION_ACTIVATE = "ACTIVATE";
    private static final String ACTION_RETIRE = "RETIRE";
    private static final String ACTION_APPROVE = "APPROVE";

    private final PolicyVersionRepository repository;
    private final ChallengeService challengeService;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;
    private final PolicyAnalyzer policyAnalyzer;
    private final ObjectMapper objectMapper;
    private final PolicySimulatedStepUpCodeCapture simulatedStepUpCodeCapture;
    private final boolean simulationEnabled;

    public PolicyLifecycleApplicationService(
            PolicyVersionRepository repository,
            ChallengeService challengeService,
            @Qualifier("decisionClock") Clock clock,
            ApplicationEventPublisher eventPublisher,
            PolicyAnalyzer policyAnalyzer,
            ObjectMapper objectMapper,
            PolicySimulatedStepUpCodeCapture simulatedStepUpCodeCapture,
            @Value("${accountshield.challenge.simulation-enabled:true}") boolean simulationEnabled) {
        this.repository = repository;
        this.challengeService = challengeService;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
        this.policyAnalyzer = policyAnalyzer;
        this.objectMapper = objectMapper;
        this.simulatedStepUpCodeCapture = simulatedStepUpCodeCapture;
        this.simulationEnabled = simulationEnabled;
    }

    @Override
    @Transactional
    public PolicyVersionSummary createDraft(CreatePolicyCommand command, String actor) {
        validateCreateCommand(command);
        validateActor(actor);
        if (repository.findByPolicyKeyAndVersion(command.policyKey(), command.version()).isPresent()) {
            throw new DuplicatePolicyVersionException(command.policyKey(), command.version());
        }
        List<PolicyVersionEntity> nonTerminal = repository.findByPolicyKeyAndStatusIn(
                command.policyKey(), PENDING_STATUSES);
        if (!nonTerminal.isEmpty()) {
            throw new PendingPolicyVersionExistsException(command.policyKey());
        }

        String definition = "{\"allowMaxScore\":" + command.allowMaxScore()
                + ",\"stepUpMaxScore\":" + command.stepUpMaxScore()
                + ",\"recoveryMaxScore\":" + command.recoveryMaxScore() + "}";
        Instant now = Instant.now(clock);
        PolicyVersionEntity entity = new PolicyVersionEntity(
                UUID.randomUUID(),
                command.policyKey(),
                command.version(),
                PolicyStatus.DRAFT.name(),
                definition,
                command.allowMaxScore(),
                command.stepUpMaxScore(),
                command.recoveryMaxScore(),
                now,
                null);
        entity.setCreatedBy(actor);
        repository.save(entity);
        return toSummary(entity);
    }

    @Override
    @Transactional
    public PolicyVersionSummary validate(String policyKey, String version, String actor) {
        validateActor(actor);
        PolicyVersionEntity entity = requirePolicy(policyKey, version);
        if (!PolicyStatus.DRAFT.name().equals(entity.getStatus())) {
            // not a legal predecessor state for VALIDATED; throws IllegalPolicyTransitionException
            // without mutating anything (transitionTo validates before it mutates)
            entity.transitionTo(PolicyStatus.VALIDATED.name(), Instant.now(clock));
        }
        PolicyAnalysisResult result = policyAnalyzer.analyze(new PolicyDefinition(
                entity.getAllowMaxScore(), entity.getStepUpMaxScore(), entity.getRecoveryMaxScore()));
        if (result.hasErrors()) {
            throw new PolicyAnalysisFailedException(policyKey, version, result);
        }
        entity.setAnalysis(objectMapper.writeValueAsString(result));
        entity.recordValidation(actor, Instant.now(clock));
        entity.transitionTo(PolicyStatus.VALIDATED.name(), Instant.now(clock));
        return toSummary(entity);
    }

    @Override
    @Transactional
    public StepUpChallenge requestApprovalStepUp(String policyKey, String version, String actor) {
        return issueStepUpChallenge(policyKey, version, ACTION_APPROVE, actor);
    }

    @Override
    @Transactional
    public PolicyVersionSummary approve(
            String policyKey, String version, UUID stepUpChallengeId, String actor, String reason) {
        consumeStepUp(policyKey, version, ACTION_APPROVE, stepUpChallengeId, actor);

        PolicyVersionEntity entity = requirePolicy(policyKey, version);
        if (!PolicyStatus.VALIDATED.name().equals(entity.getStatus())) {
            // not a legal predecessor state for APPROVED; throws IllegalPolicyTransitionException
            // without mutating anything (transitionTo validates before it mutates)
            entity.transitionTo(PolicyStatus.APPROVED.name(), Instant.now(clock));
        }
        if (actor.equals(entity.getCreatedBy())) {
            throw new SelfApprovalNotAllowedException(policyKey, version, actor);
        }
        validateReason(reason);
        entity.recordApproval(actor, Instant.now(clock), reason);
        entity.transitionTo(PolicyStatus.APPROVED.name(), Instant.now(clock));
        return toSummary(entity);
    }

    @Override
    @Transactional
    public PolicyVersionSummary activate(String policyKey, String version, UUID stepUpChallengeId, String actor) {
        consumeStepUp(policyKey, version, ACTION_ACTIVATE, stepUpChallengeId, actor);

        PolicyVersionEntity candidate = requirePolicy(policyKey, version);
        repository.findByPolicyKeyAndStatus(policyKey, ACTIVE)
                .ifPresent(current -> current.transitionTo(
                        PolicyStatus.RETIRED.name(), Instant.now(clock)));
        Instant activatedAt = Instant.now(clock);
        candidate.transitionTo(PolicyStatus.ACTIVE.name(), activatedAt);
        eventPublisher.publishEvent(new PolicyActivated(policyKey, version, activatedAt));
        return toSummary(candidate);
    }

    @Override
    @Transactional
    public PolicyVersionSummary reject(String policyKey, String version) {
        PolicyVersionEntity entity = requirePolicy(policyKey, version);
        entity.transitionTo(PolicyStatus.REJECTED.name(), Instant.now(clock));
        return toSummary(entity);
    }

    @Override
    @Transactional
    public PolicyVersionSummary retire(String policyKey, String version, UUID stepUpChallengeId, String actor) {
        consumeStepUp(policyKey, version, ACTION_RETIRE, stepUpChallengeId, actor);

        PolicyVersionEntity entity = requirePolicy(policyKey, version);
        entity.transitionTo(PolicyStatus.RETIRED.name(), Instant.now(clock));
        return toSummary(entity);
    }

    @Override
    @Transactional
    public StepUpChallenge requestActivationStepUp(String policyKey, String version, String actor) {
        return issueStepUpChallenge(policyKey, version, ACTION_ACTIVATE, actor);
    }

    @Override
    @Transactional
    public StepUpChallenge requestRetirementStepUp(String policyKey, String version, String actor) {
        return issueStepUpChallenge(policyKey, version, ACTION_RETIRE, actor);
    }

    private StepUpChallenge issueStepUpChallenge(String policyKey, String version, String action, String actor) {
        validateKey(policyKey);
        validateVersion(version);
        UUID contextId = stepUpContextId(policyKey, version, action);
        UUID challengeId = challengeService.create(new CreateChallengeCommand(
                actor,
                ChallengeType.TOTP_SIMULATED,
                ChallengePurpose.PRIVILEGED_OPERATION,
                contextId)).challengeId();
        String simulatedCode = simulationEnabled ? simulatedStepUpCodeCapture.consume(challengeId) : null;
        return new StepUpChallenge(challengeId, simulatedCode, contextId);
    }

    private void consumeStepUp(
            String policyKey, String version, String action, UUID stepUpChallengeId, String actor) {
        UUID contextId = stepUpContextId(policyKey, version, action);
        try {
            challengeService.consume(new ConsumeChallengeCommand(
                    stepUpChallengeId, actor, ChallengePurpose.PRIVILEGED_OPERATION, contextId));
            eventPublisher.publishEvent(
                    new PrivilegedPolicyActionAttempted(policyKey, version, action, actor, true));
        } catch (RuntimeException exception) {
            eventPublisher.publishEvent(
                    new PrivilegedPolicyActionAttempted(policyKey, version, action, actor, false));
            throw exception;
        }
    }

    private UUID stepUpContextId(String policyKey, String version, String action) {
        return UUID.nameUUIDFromBytes(
                ("policy:" + action + ":" + policyKey + ":" + version).getBytes(StandardCharsets.UTF_8));
    }

    private PolicyVersionEntity requirePolicy(String policyKey, String version) {
        validateKey(policyKey);
        validateVersion(version);
        return repository.findByPolicyKeyAndVersion(policyKey, version)
                .orElseThrow(() -> new PolicyVersionNotFoundException(policyKey, version));
    }

    private void validateCreateCommand(CreatePolicyCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateKey(command.policyKey());
        validateVersion(command.version());
        if (command.allowMaxScore() < 0 || command.allowMaxScore() > 99) {
            throw new IllegalArgumentException("allowMaxScore must be between 0 and 99");
        }
        if (command.stepUpMaxScore() < 1 || command.stepUpMaxScore() > 99) {
            throw new IllegalArgumentException("stepUpMaxScore must be between 1 and 99");
        }
        if (command.allowMaxScore() >= command.stepUpMaxScore()) {
            throw new IllegalArgumentException("allowMaxScore must be less than stepUpMaxScore");
        }
        if (command.recoveryMaxScore() < 0 || command.recoveryMaxScore() > 99) {
            throw new IllegalArgumentException("recoveryMaxScore must be between 0 and 99");
        }
    }

    private void validateKey(String policyKey) {
        Objects.requireNonNull(policyKey, "policyKey must not be null");
        if (policyKey.isBlank() || policyKey.length() > 100) {
            throw new IllegalArgumentException("policyKey must contain between 1 and 100 characters");
        }
    }

    private void validateVersion(String version) {
        Objects.requireNonNull(version, "version must not be null");
        if (version.isBlank() || version.length() > 40) {
            throw new IllegalArgumentException("version must contain between 1 and 40 characters");
        }
    }

    private void validateActor(String actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        if (actor.isBlank() || actor.length() > 200) {
            throw new IllegalArgumentException("actor must contain between 1 and 200 characters");
        }
    }

    private void validateReason(String reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("reason must contain between 1 and 500 characters");
        }
    }

    private PolicyVersionSummary toSummary(PolicyVersionEntity entity) {
        return new PolicyVersionSummary(
                entity.getId(),
                entity.getPolicyKey(),
                entity.getVersion(),
                PolicyStatus.valueOf(entity.getStatus()),
                entity.getAllowMaxScore(),
                entity.getStepUpMaxScore(),
                entity.getRecoveryMaxScore(),
                entity.getCreatedAt(),
                entity.getActivatedAt(),
                entity.getAnalysis() == null
                        ? null
                        : objectMapper.readValue(entity.getAnalysis(), PolicyAnalysisResult.class),
                entity.getCreatedBy() == null
                        ? null
                        : new PolicyGovernance(
                                entity.getCreatedBy(),
                                entity.getValidatedBy(),
                                entity.getValidatedAt(),
                                entity.getApprovedBy(),
                                entity.getApprovedAt(),
                                entity.getApprovalReason()));
    }
}
