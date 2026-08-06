package io.github.viniciusssantos.accountshield.recovery.internal.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecoveryAuthorizationRepository
        extends JpaRepository<RecoveryAuthorizationEntity, UUID> {

    Optional<RecoveryAuthorizationEntity> findByDecisionId(UUID decisionId);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RecoveryAuthorizationEntity> findById(UUID authorizationId);

    /**
     * Only deletes authorizations no recovery flow still references -- an authorization that
     * spawned a flow (terminal or not) must outlive that flow's own foreign key, or deletion
     * would violate fk_recovery_flow_authorization. A flow that has itself been purged by
     * {@code RecoveryFlowRetentionCleanup} no longer blocks its originating authorization.
     */
    @Modifying
    @Query(value = """
            DELETE FROM recovery.recovery_authorization
             WHERE expires_at < :cutoff
               AND NOT EXISTS (
                   SELECT 1 FROM recovery.recovery_flow
                    WHERE recovery_flow.authorization_id = recovery_authorization.id
               )
            """, nativeQuery = true)
    int deleteExpiredAndUnreferenced(@Param("cutoff") Instant cutoff);
}
