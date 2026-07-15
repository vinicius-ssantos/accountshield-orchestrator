package io.github.viniciusssantos.accountshield.audit.internal.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionTraceRepository extends JpaRepository<DecisionTraceEntity, UUID> {

    Optional<DecisionTraceEntity> findByProtectionRequestId(UUID protectionRequestId);
}
