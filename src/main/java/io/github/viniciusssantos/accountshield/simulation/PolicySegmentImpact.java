package io.github.viniciusssantos.accountshield.simulation;

public record PolicySegmentImpact(
        String segment,
        int totalDecisions,
        int divergentDecisions) {

    public double divergencePercentage() {
        return totalDecisions == 0 ? 0.0 : (divergentDecisions * 100.0) / totalDecisions;
    }
}
