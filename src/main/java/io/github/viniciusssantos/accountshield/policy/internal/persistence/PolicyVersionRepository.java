package io.github.viniciusssantos.accountshield.policy.internal.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyVersionRepository extends JpaRepository<PolicyVersionEntity, UUID> {

    Optional<PolicyVersionEntity> findByPolicyKeyAndVersion(String policyKey, String version);
}
