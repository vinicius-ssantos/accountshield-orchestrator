package io.github.viniciusssantos.accountshield.risk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RiskReasonCatalogTest {

    @Test
    void recognizesEveryCodeTheAlgorithmCanEmit() {
        assertThat(RiskReasonCatalog.KNOWN_CODES).containsExactlyInAnyOrder(
                "COMPROMISED_CREDENTIAL", "IMPOSSIBLE_TRAVEL", "FAILED_ATTEMPTS",
                "NETWORK_RISK_LOW", "NETWORK_RISK_MEDIUM", "NETWORK_RISK_HIGH",
                "NEW_DEVICE", "LOW_CONFIDENCE_SIGNAL");
    }

    @Test
    void rejectsAnUnknownCode() {
        assertThat(RiskReasonCatalog.KNOWN_CODES).doesNotContain("SOME_RETIRED_CODE");
    }

    @Test
    void exposesAVersionIdentifier() {
        assertThat(RiskReasonCatalog.CURRENT_VERSION).isEqualTo("risk-reason-catalog-1.0");
    }
}
