package io.github.viniciusssantos.accountshield.risk;

import java.util.Set;

/**
 * The set of reason codes {@code DeterministicRiskAssessmentService} can currently emit, and the
 * version identifying that set. Replay uses this to detect when a historical decision trace
 * references a reason code the catalog no longer recognizes (renamed or retired) rather than
 * silently accepting it as still valid.
 */
public final class RiskReasonCatalog {

    public static final String CURRENT_VERSION = "risk-reason-catalog-1.0";

    public static final Set<String> KNOWN_CODES = Set.of(
            "COMPROMISED_CREDENTIAL",
            "IMPOSSIBLE_TRAVEL",
            "FAILED_ATTEMPTS",
            "NETWORK_RISK_LOW",
            "NETWORK_RISK_MEDIUM",
            "NETWORK_RISK_HIGH",
            "NEW_DEVICE",
            "LOW_CONFIDENCE_SIGNAL");

    private RiskReasonCatalog() {
    }
}
