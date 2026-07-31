package io.github.viniciusssantos.accountshield.policy.internal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PolicyVersionRepository extends JpaRepository<PolicyVersionEntity, UUID> {

    Optional<PolicyVersionEntity> findByPolicyKeyAndVersion(String policyKey, String version);

    Optional<PolicyVersionEntity> findByPolicyKeyAndStatus(String policyKey, String status);

    List<PolicyVersionEntity> findByPolicyKeyAndStatusIn(String policyKey, List<String> statuses);

    List<PolicyVersionEntity> findByPolicyKeyOrderByCreatedAtDesc(String policyKey);

    @Query("SELECT DISTINCT e.policyKey FROM PolicyVersionEntity e ORDER BY e.policyKey")
    List<String> findDistinctPolicyKeys(Pageable pageable);
}
