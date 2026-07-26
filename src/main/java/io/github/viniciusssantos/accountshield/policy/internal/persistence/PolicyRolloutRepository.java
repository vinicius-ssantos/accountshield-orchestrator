package io.github.viniciusssantos.accountshield.policy.internal.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRolloutRepository extends JpaRepository<PolicyRolloutEntity, UUID> {

    Optional<PolicyRolloutEntity> findByPolicyKeyAndStatus(String policyKey, String status);
}
