package io.github.viniciusssantos.accountshield.recovery.internal;

import io.github.viniciusssantos.accountshield.recovery.RecoveryInvestigationQuery;
import io.github.viniciusssantos.accountshield.recovery.RecoveryInvestigationQuery.RecoveryInvestigationView;
import io.github.viniciusssantos.accountshield.recovery.internal.persistence.RecoveryAuthorizationEntity;
import io.github.viniciusssantos.accountshield.recovery.internal.persistence.RecoveryAuthorizationRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JpaRecoveryInvestigationQuery implements RecoveryInvestigationQuery {

    private final RecoveryAuthorizationRepository repository;

    public JpaRecoveryInvestigationQuery(RecoveryAuthorizationRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RecoveryInvestigationView> findByDecisionId(UUID decisionId) {
        Objects.requireNonNull(decisionId, "decisionId must not be null");
        return repository.findByDecisionId(decisionId).map(this::toView);
    }

    private RecoveryInvestigationView toView(RecoveryAuthorizationEntity entity) {
        return new RecoveryInvestigationView(
                entity.getId(),
                entity.getDirective(),
                entity.getIssuedAt(),
                entity.getExpiresAt(),
                entity.getConsumedAt());
    }
}
