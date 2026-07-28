package io.github.viniciusssantos.accountshieldsdk.model;

import java.util.List;

/** Mirrors {@code POST /api/v1/evidence/verify}'s response body. */
public record EvidenceVerificationResult(boolean valid, List<String> problems) {
}
