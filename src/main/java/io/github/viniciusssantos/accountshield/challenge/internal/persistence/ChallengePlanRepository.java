package io.github.viniciusssantos.accountshield.challenge.internal.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChallengePlanRepository extends JpaRepository<ChallengePlanEntity, UUID> {
}
