package io.github.viniciusssantos.accountshieldsdk.model;

import java.util.List;

/** Mirrors {@code POST /api/v1/policies/analyze}'s response body. */
public record PolicyAnalysisResult(String analyzerVersion, List<PolicyDiagnostic> diagnostics) {

    /** {@code true} if any diagnostic has {@code severity == "ERROR"}. */
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(diagnostic -> "ERROR".equals(diagnostic.severity()));
    }

    public record PolicyDiagnostic(String code, String severity, String path, String message) {
    }
}
