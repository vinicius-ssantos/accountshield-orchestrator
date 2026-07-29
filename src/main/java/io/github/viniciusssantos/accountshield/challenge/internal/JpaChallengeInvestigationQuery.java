package io.github.viniciusssantos.accountshield.challenge.internal;

import io.github.viniciusssantos.accountshield.challenge.ChallengeInvestigationQuery;
import io.github.viniciusssantos.accountshield.challenge.ChallengeInvestigationQuery.ChallengeInvestigationView;
import io.github.viniciusssantos.accountshield.challenge.internal.persistence.ChallengePlanEntity;
import io.github.viniciusssantos.accountshield.challenge.internal.persistence.ChallengePlanRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JpaChallengeInvestigationQuery implements ChallengeInvestigationQuery {

    private final ChallengePlanRepository repository;

    public JpaChallengeInvestigationQuery(ChallengePlanRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChallengeInvestigationView> findByContextId(UUID contextId) {
        Objects.requireNonNull(contextId, "contextId must not be null");
        return repository.findByContextIdOrderByCreatedAtAscIdAsc(contextId).stream()
                .map(this::toView)
                .toList();
    }

    private ChallengeInvestigationView toView(ChallengePlanEntity entity) {
        return new ChallengeInvestigationView(
                entity.getId(),
                entity.getChallengeType(),
                entity.getPurpose(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getExpiresAt(),
                entity.getConsumedAt());
    }
}
