package io.github.viniciusssantos.accountshield.policy;

import java.util.List;

public record PolicyAnalysisResult(String analyzerVersion, List<PolicyDiagnostic> diagnostics) {

    public static final String CURRENT_ANALYZER_VERSION = "policy-analyzer-1.0";

    public PolicyAnalysisResult {
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == PolicySeverity.ERROR);
    }
}
