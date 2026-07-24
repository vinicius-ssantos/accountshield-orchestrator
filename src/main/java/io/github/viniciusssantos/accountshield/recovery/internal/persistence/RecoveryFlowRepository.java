package io.github.viniciusssantos.accountshield.recovery.internal.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecoveryFlowRepository extends JpaRepository<RecoveryFlowEntity, UUID> {

    boolean existsByOriginatingDecisionId(UUID originatingDecisionId);
}
