package io.github.viniciusssantos.accountshield.simulation;

public record PolicyComparisonSummary(
        String policyKey,
        String originalVersion,
        String candidateVersion,
        int totalDecisions,
        int divergentDecisions,
        int allowToStepUp,
        int allowToBlock,
        int stepUpToBlock,
        int stepUpToAllow,
        int blockToStepUp,
        int blockToAllow) {

    public int convergedDecisions() {
        return totalDecisions - divergentDecisions;
    }
}
