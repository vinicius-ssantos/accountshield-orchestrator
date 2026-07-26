package io.github.viniciusssantos.accountshield.risk.internal;

import io.github.viniciusssantos.accountshield.risk.RiskAlgorithmRegistry;
import io.github.viniciusssantos.accountshield.risk.RiskAssessmentService;
import io.github.viniciusssantos.accountshield.risk.UnknownAlgorithmVersionException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class InMemoryRiskAlgorithmRegistry implements RiskAlgorithmRegistry {

    private final Map<String, RiskAssessmentService> byVersion;

    InMemoryRiskAlgorithmRegistry(List<RiskAssessmentService> implementations) {
        Map<String, RiskAssessmentService> registered = new HashMap<>();
        for (RiskAssessmentService implementation : implementations) {
            String version = implementation.algorithmVersion();
            RiskAssessmentService existing = registered.put(version, implementation);
            if (existing != null) {
                throw new IllegalStateException(
                        "multiple risk algorithm implementations registered for version: " + version);
            }
        }
        this.byVersion = Map.copyOf(registered);
    }

    @Override
    public RiskAssessmentService resolve(String algorithmVersion) {
        RiskAssessmentService service = byVersion.get(algorithmVersion);
        if (service == null) {
            throw new UnknownAlgorithmVersionException(algorithmVersion);
        }
        return service;
    }
}
