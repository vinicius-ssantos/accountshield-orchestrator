package io.github.viniciusssantos.accountshield.recovery.internal.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RecoveryFlowRepository extends JpaRepository<RecoveryFlowEntity, UUID> {

    boolean existsByOriginatingDecisionId(UUID originatingDecisionId);

    Optional<RecoveryFlowEntity> findByAuthorizationId(UUID authorizationId);

    @Modifying
    @Query("DELETE FROM RecoveryFlowEntity e WHERE e.status IN :statuses AND e.updatedAt < :cutoff")
    int deleteByStatusInAndUpdatedAtBefore(Collection<String> statuses, Instant cutoff);
}
