package io.github.viniciusssantos.accountshield.risk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.risk.RiskAssessmentService;
import io.github.viniciusssantos.accountshield.risk.UnknownAlgorithmVersionException;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryRiskAlgorithmRegistryTest {

    @Test
    void resolvesTheRegisteredVersion() {
        RiskAssessmentService v1 = stubImplementation("risk-rules-1.0");
        InMemoryRiskAlgorithmRegistry registry = new InMemoryRiskAlgorithmRegistry(List.of(v1));

        assertThat(registry.resolve("risk-rules-1.0")).isSameAs(v1);
    }

    @Test
    void resolvesAmongMultipleRegisteredVersions() {
        RiskAssessmentService v1 = stubImplementation("risk-rules-1.0");
        RiskAssessmentService v2 = stubImplementation("risk-rules-2.0");
        InMemoryRiskAlgorithmRegistry registry = new InMemoryRiskAlgorithmRegistry(List.of(v1, v2));

        assertThat(registry.resolve("risk-rules-1.0")).isSameAs(v1);
        assertThat(registry.resolve("risk-rules-2.0")).isSameAs(v2);
    }

    @Test
    void throwsForAnUnregisteredVersion() {
        InMemoryRiskAlgorithmRegistry registry =
                new InMemoryRiskAlgorithmRegistry(List.of(stubImplementation("risk-rules-1.0")));

        assertThatThrownBy(() -> registry.resolve("risk-rules-9.9"))
                .isInstanceOf(UnknownAlgorithmVersionException.class);
    }

    @Test
    void failsFastOnDuplicateRegistration() {
        RiskAssessmentService first = stubImplementation("risk-rules-1.0");
        RiskAssessmentService duplicate = stubImplementation("risk-rules-1.0");

        assertThatThrownBy(() -> new InMemoryRiskAlgorithmRegistry(List.of(first, duplicate)))
                .isInstanceOf(IllegalStateException.class);
    }

    private RiskAssessmentService stubImplementation(String version) {
        RiskAssessmentService stub = mock(RiskAssessmentService.class);
        when(stub.algorithmVersion()).thenReturn(version);
        return stub;
    }
}
