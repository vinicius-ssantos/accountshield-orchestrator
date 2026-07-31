package io.github.viniciusssantos.accountshield.policy.internal;

import io.github.viniciusssantos.accountshield.policy.PolicyAnalysisResult;
import io.github.viniciusssantos.accountshield.policy.PolicyDirectoryQuery;
import io.github.viniciusssantos.accountshield.policy.PolicyDirectoryQuery.PolicyLifecycleDetail;
import io.github.viniciusssantos.accountshield.policy.PolicyDirectoryQuery.PolicySummary;
import io.github.viniciusssantos.accountshield.policy.PolicyDirectoryQuery.RoutingScopeEntry;
import io.github.viniciusssantos.accountshield.policy.PolicyGovernance;
import io.github.viniciusssantos.accountshield.policy.PolicyRolloutStatus;
import io.github.viniciusssantos.accountshield.policy.PolicyStatus;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionSummary;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.ClientPolicyRouteEntity;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.ClientPolicyRouteRepository;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyRolloutRepository;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionEntity;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
public class PolicyDirectoryQueryService implements PolicyDirectoryQuery {

    private static final String ACTIVE_ROLLOUT = PolicyRolloutStatus.ACTIVE.name();

    private final PolicyVersionRepository versionRepository;
    private final ClientPolicyRouteRepository routeRepository;
    private final PolicyRolloutRepository rolloutRepository;
    private final ObjectMapper objectMapper;

    public PolicyDirectoryQueryService(
            PolicyVersionRepository versionRepository,
            ClientPolicyRouteRepository routeRepository,
            PolicyRolloutRepository rolloutRepository,
            ObjectMapper objectMapper) {
        this.versionRepository = versionRepository;
        this.routeRepository = routeRepository;
        this.rolloutRepository = rolloutRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicySummary> search() {
        List<String> policyKeys = versionRepository.findDistinctPolicyKeys(
                PageRequest.of(0, MAX_POLICY_KEYS));
        return policyKeys.stream().map(this::toSummary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PolicyLifecycleDetail> investigate(String policyKey) {
        Objects.requireNonNull(policyKey, "policyKey must not be null");
        List<PolicyVersionEntity> entities = versionRepository.findByPolicyKeyOrderByCreatedAtDesc(policyKey);
        if (entities.isEmpty()) {
            return Optional.empty();
        }

        List<PolicyVersionSummary> versions = entities.stream().map(this::toVersionSummary).toList();
        List<RoutingScopeEntry> routingScope = routeRepository
                .findByPolicyKeyOrderByClientIdAscEventTypeAsc(policyKey).stream()
                .map(route -> new RoutingScopeEntry(route.getClientId(), route.getEventType()))
                .toList();

        return Optional.of(new PolicyLifecycleDetail(policyKey, versions, routingScope));
    }

    private PolicySummary toSummary(String policyKey) {
        List<PolicyVersionEntity> entities = versionRepository.findByPolicyKeyOrderByCreatedAtDesc(policyKey);
        PolicyVersionEntity active = entities.stream()
                .filter(entity -> PolicyStatus.ACTIVE.name().equals(entity.getStatus()))
                .findFirst()
                .orElse(null);
        boolean hasActiveRollout = rolloutRepository.findByPolicyKeyAndStatus(policyKey, ACTIVE_ROLLOUT).isPresent();

        return new PolicySummary(
                policyKey,
                entities.size(),
                active == null ? null : active.getVersion(),
                active == null ? null : active.getActivatedAt(),
                hasActiveRollout);
    }

    private PolicyVersionSummary toVersionSummary(PolicyVersionEntity entity) {
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
