package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionInvestigationCriteria;
import io.github.viniciusssantos.accountshield.audit.DecisionInvestigationQuery.DecisionInvestigationPage;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignalEnvelope;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import io.github.viniciusssantos.accountshield.risk.SignalConfidence;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class DecisionInvestigationIntegrationTest {

    @Autowired private ProtectionDecisionService protectionDecisionService;
    @Autowired private DecisionInvestigationQuery investigationQuery;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearCorrelationContext() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    @Test
    void persistsCorrelationAndReturnsAStableMinimizedCursorPage() {
        String correlationId = "investigation-" + UUID.randomUUID();
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);

        ProtectionDecisionResult low = decide(
                new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW));
        ProtectionDecisionResult medium = decide(
                new RiskSignals(10, false, false, false, NetworkRiskLevel.LOW));
        ProtectionDecisionResult high = decide(
                new RiskSignals(0, false, true, true, NetworkRiskLevel.LOW));

        assertThat(jdbcTemplate.queryForList(
                "SELECT correlation_id FROM audit.decision_trace WHERE id IN (?, ?, ?) ORDER BY id",
                String.class,
                low.decisionId(), medium.decisionId(), high.decisionId()))
                .containsOnly(correlationId);

        DecisionInvestigationPage first = investigationQuery.search(criteria(
                correlationId, null, null, null, null, 1));
        DecisionInvestigationPage second = investigationQuery.search(criteria(
                correlationId, null, null, null, first.nextCursor(), 1));

        assertThat(first.decisions()).hasSize(1);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(second.decisions()).hasSize(1);
        assertThat(second.decisions().get(0).decisionReference())
                .isNotEqualTo(first.decisions().get(0).decisionReference());

        Set<String> returnedReferences = new HashSet<>();
        returnedReferences.add(first.decisions().get(0).decisionReference());
        returnedReferences.add(second.decisions().get(0).decisionReference());
        assertThat(returnedReferences).hasSize(2);

        var summary = first.decisions().get(0);
        assertThat(summary.correlationId()).isEqualTo(correlationId);
        assertThat(summary.eventType()).isEqualTo("LOGIN_ATTEMPT");
        assertThat(summary.policyKey()).isEqualTo("account-protection-default");
        assertThat(summary.simulated()).isTrue();
        assertThat(summary.provenanceAvailable()).isTrue();
    }

    @Test
    void appliesRiskBandFilterWithinTheExactCorrelationScope() {
        String correlationId = "investigation-filter-" + UUID.randomUUID();
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);

        decide(new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW));
        ProtectionDecisionResult high = decide(
                new RiskSignals(0, false, true, true, NetworkRiskLevel.LOW));

        DecisionInvestigationPage page = investigationQuery.search(criteria(
                correlationId, "LOGIN_ATTEMPT", null, "HIGH", null, 25));

        assertThat(page.decisions())
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.decisionReference()).isEqualTo(high.decisionId().toString());
                    assertThat(summary.riskBand()).isEqualTo("HIGH");
                    assertThat(summary.riskScore()).isGreaterThanOrEqualTo(70);
                });
    }

    @Test
    void rejectsMalformedCursorWithoutExposingPersistenceDetails() {
        assertThatThrownBy(() -> investigationQuery.search(criteria(
                null, null, null, null, "not-a-valid-cursor", 25)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid decision search cursor");
    }

    private DecisionInvestigationCriteria criteria(
            String correlationId,
            String eventType,
            String outcome,
            String riskBand,
            String cursor,
            int pageSize) {
        return new DecisionInvestigationCriteria(
                correlationId,
                eventType,
                outcome,
                riskBand,
                null,
                null,
                null,
                cursor,
                pageSize);
    }

    private ProtectionDecisionResult decide(RiskSignals signals) {
        return protectionDecisionService.decide(new ProtectionDecisionCommand(
                "investigation-account-" + UUID.randomUUID(),
                ProtectionEventType.LOGIN_ATTEMPT,
                new RiskSignalEnvelope(
                        signals,
                        "CLIENT_SUPPLIED",
                        Instant.now(),
                        SignalConfidence.HIGH,
                        null,
                        true),
                "investigation-idem-" + UUID.randomUUID()));
    }
}
