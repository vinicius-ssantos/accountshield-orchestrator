package io.github.viniciusssantos.accountshield.policy;

public record PolicyDefinition(
        Short allowMaxScore,
        Short stepUpMaxScore,
        Short recoveryMaxScore) {
}
