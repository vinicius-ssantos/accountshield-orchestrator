package io.github.viniciusssantos.accountshield.recovery.internal.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecoveryAuthorizationRepository
        extends JpaRepository<RecoveryAuthorizationEntity, UUID> {

    Optional<RecoveryAuthorizationEntity> findByDecisionId(UUID decisionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select authorization from RecoveryAuthorizationEntity authorization "
            + "where authorization.id = :authorizationId")
    Optional<RecoveryAuthorizationEntity> findByIdForUpdate(
            @Param("authorizationId") UUID authorizationId);
}
