package io.github.viniciusssantos.accountshield.protection;

/**
 * Identifies the version of the decision-orchestration flow itself (how risk assessment,
 * policy evaluation, and challenge issuance are combined) — distinct from the risk-algorithm
 * version, which identifies only the scoring rules. Bump this when the orchestration sequence
 * changes in a way that could affect replay comparisons, even if no individual algorithm did.
 */
public final class DecisionEngineVersion {

    public static final String CURRENT = "decision-engine-1.0";

    private DecisionEngineVersion() {
    }
}
