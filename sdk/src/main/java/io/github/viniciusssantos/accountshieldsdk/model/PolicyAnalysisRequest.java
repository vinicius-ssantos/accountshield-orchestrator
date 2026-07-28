package io.github.viniciusssantos.accountshieldsdk.model;

/** Mirrors {@code POST /api/v1/policies/analyze}'s request body. Any null field triggers a {@code *_MISSING} diagnostic. */
public record PolicyAnalysisRequest(Integer allowMaxScore, Integer stepUpMaxScore, Integer recoveryMaxScore) {
}
