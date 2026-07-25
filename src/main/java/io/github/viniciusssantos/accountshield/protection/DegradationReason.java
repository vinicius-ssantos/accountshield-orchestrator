package io.github.viniciusssantos.accountshield.protection;

/**
 * Catalog of critical-dependency failures this module knows how to degrade safely.
 */
public enum DegradationReason {

    ACTIVE_POLICY_UNAVAILABLE(DegradationStrategy.FAIL_CLOSED, false),
    RISK_SIGNAL_STALE(DegradationStrategy.REJECT_UNAVAILABLE, false),
    CHALLENGE_PROVIDER_UNAVAILABLE(DegradationStrategy.FAIL_CLOSED, true);

    private final DegradationStrategy strategy;
    private final boolean producesDecision;

    DegradationReason(DegradationStrategy strategy, boolean producesDecision) {
        this.strategy = strategy;
        this.producesDecision = producesDecision;
    }

    public DegradationStrategy strategy() {
        return strategy;
    }

    /**
     * Whether this reason still results in a persisted decision (vs. refusing the request outright).
     */
    public boolean producesDecision() {
        return producesDecision;
    }
}
