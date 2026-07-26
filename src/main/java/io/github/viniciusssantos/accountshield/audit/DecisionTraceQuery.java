package io.github.viniciusssantos.accountshield.audit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DecisionTraceQuery {

    Optional<DecisionTraceView> findByProtectionRequestId(UUID protectionRequestId);

    /**
     * The most recent traces decided under the given policy key, newest first, bounded by
     * {@code maxSamples} so historical-analysis callers can never trigger an unbounded scan.
     */
    List<DecisionTraceView> findRecentByPolicyKey(String policyKey, int maxSamples);
}
