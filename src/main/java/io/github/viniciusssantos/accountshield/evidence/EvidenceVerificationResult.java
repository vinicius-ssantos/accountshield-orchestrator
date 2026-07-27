package io.github.viniciusssantos.accountshield.evidence;

import java.util.List;

public record EvidenceVerificationResult(boolean valid, List<String> problems) {

    public EvidenceVerificationResult {
        problems = List.copyOf(problems);
    }

    public static EvidenceVerificationResult ok() {
        return new EvidenceVerificationResult(true, List.of());
    }

    public static EvidenceVerificationResult failed(List<String> problems) {
        return new EvidenceVerificationResult(false, problems);
    }
}
