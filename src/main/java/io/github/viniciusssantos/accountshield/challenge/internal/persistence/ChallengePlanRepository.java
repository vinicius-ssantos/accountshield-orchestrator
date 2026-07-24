package io.github.viniciusssantos.accountshield.challenge.internal.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChallengePlanRepository extends JpaRepository<ChallengePlanEntity, UUID> {

    int deleteByStatusInAndExpiresAtBefore(List<String> statuses, Instant cutoff);
}
