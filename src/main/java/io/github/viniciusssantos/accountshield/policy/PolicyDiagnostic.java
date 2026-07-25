package io.github.viniciusssantos.accountshield.policy;

public record PolicyDiagnostic(
        String code,
        PolicySeverity severity,
        String path,
        String message) {
}
