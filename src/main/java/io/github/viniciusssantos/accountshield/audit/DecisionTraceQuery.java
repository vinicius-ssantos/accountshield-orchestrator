package io.github.viniciusssantos.accountshield.audit;

import java.util.Optional;
import java.util.UUID;

public interface DecisionTraceQuery {

    Optional<DecisionTraceView> findByProtectionRequestId(UUID protectionRequestId);
}
