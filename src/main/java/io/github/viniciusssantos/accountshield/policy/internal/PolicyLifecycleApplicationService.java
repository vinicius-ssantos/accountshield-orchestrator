package io.github.viniciusssantos.accountshield.policy.internal;

import io.github.viniciusssantos.accountshield.policy.CreatePolicyCommand;
import io.github.viniciusssantos.accountshield.policy.IllegalPolicyTransitionException;
import io.github.viniciusssantos.accountshield.policy.PolicyActivated;
import io.github.viniciusssantos.accountshield.policy.PolicyLifecycleService;
import io.github.viniciusssantos.accountshield.policy.PolicyStatus;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionSummary;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionEntity;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyLifecycleApplicationService implements PolicyLifecycleService {

    private static final String ACTIVE = PolicyStatus.ACTIVE.name();
    private static final List<String> PENDING_STATUSES = List.of(
            PolicyStatus.DRAFT.name(),
            PolicyStatus.VALIDATED.name());

    private final PolicyVersionRepository repository;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    public PolicyLifecycleApplicationService(
            PolicyVersionRepository repository,
            @Qualifier("decisionClock") Clock clock,
            ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public PolicyVersionSummary createDraft(CreatePolicyCommand command) {
        validateCreateCommand(command);
        if (repository.findByPolicyKeyAndVersion(command.policyKey(), command.version()).isPresent()) {
            throw new IllegalStateException(
                    "policy version already exists: " + command.policyKey() + ":" + command.version());
        }
        List<PolicyVersionEntity> nonTerminal = repository.findByPolicyKeyAndStatusIn(
                command.policyKey(), PENDING_STATUSES);
        if (!nonTerminal.isEmpty()) {
            throw new IllegalStateException(
                    "a draft or validated policy version already exists for key: " + command.policyKey());
        }

        String definition = "{\"allowMaxScore\":" + command.allowMaxScore()
                + ",\"stepUpMaxScore\":" + command.stepUpMaxScore() + "}";
        Instant now = Instant.now(clock);
        PolicyVersionEntity entity = new PolicyVersionEntity(
                UUID.randomUUID(),
                command.policyKey(),
                command.version(),
                PolicyStatus.DRAFT.name(),
                definition,
                command.allowMaxScore(),
                command.stepUpMaxScore(),
                now,
                null);
        repository.save(entity);
        return toSummary(entity);
    }

    @Override
    @Transactional
    public PolicyVersionSummary validate(String policyKey, String version) {
        PolicyVersionEntity entity = requirePolicy(policyKey, version);
        entity.transitionTo(PolicyStatus.VALIDATED.name(), Instant.now(clock));
        return toSummary(entity);
    }

    @Override
    @Transactional
    public PolicyVersionSummary activate(String policyKey, String version) {
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
    public PolicyVersionSummary retire(String policyKey, String version) {
        PolicyVersionEntity entity = requirePolicy(policyKey, version);
        entity.transitionTo(PolicyStatus.RETIRED.name(), Instant.now(clock));
        return toSummary(entity);
    }

    private PolicyVersionEntity requirePolicy(String policyKey, String version) {
        validateKey(policyKey);
        validateVersion(version);
        return repository.findByPolicyKeyAndVersion(policyKey, version)
                .orElseThrow(() -> new IllegalStateException(
                        "policy version not found: " + policyKey + ":" + version));
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

    private PolicyVersionSummary toSummary(PolicyVersionEntity entity) {
        return new PolicyVersionSummary(
                entity.getId(),
                entity.getPolicyKey(),
                entity.getVersion(),
                PolicyStatus.valueOf(entity.getStatus()),
                entity.getAllowMaxScore(),
                entity.getStepUpMaxScore(),
                entity.getCreatedAt(),
                entity.getActivatedAt());
    }
}
