package io.github.viniciusssantos.accountshield.recovery.internal.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RecoveryAuthorizationRepository
        extends JpaRepository<RecoveryAuthorizationEntity, UUID> {

    Optional<RecoveryAuthorizationEntity> findByDecisionId(UUID decisionId);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RecoveryAuthorizationEntity> findById(UUID authorizationId);
}
