package io.github.viniciusssantos.accountshield.protection.internal.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProtectionRequestRepository extends JpaRepository<ProtectionRequestEntity, UUID> {
}
