package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.investigation.DecisionReplayQuery;
import io.github.viniciusssantos.accountshield.investigation.DecisionReplayQuery.DecisionReplayComparison;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignalEnvelope;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import io.github.viniciusssantos.accountshield.risk.SignalConfidence;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class DecisionReplayIntegrationTest {

    @Autowired private ProtectionDecisionService protectionDecisionService;
    @Autowired private DecisionReplayQuery replayQuery;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void replaysAMatchingLowRiskDecisionWithoutSideEffects() {
        String rawAccountReference = "replay-account-" + UUID.randomUUID();
        ProtectionDecisionResult decision = decide(
                new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW), rawAccountReference);

        long outboxBefore = countRows("outbox.outbox_event");
        long recoveryBefore = countRows("recovery.recovery_flow");
        long challengeBefore = countRows("challenge.challenge_plan");

        DecisionReplayComparison comparison = replayQuery
                .replay(decision.decisionId().toString())
                .orElseThrow();

        assertThat(comparison.decisionReference()).isEqualTo(decision.decisionId().toString());
        assertThat(comparison.maskedSubjectReference())
                .startsWith("••••")
                .endsWith(rawAccountReference.substring(rawAccountReference.length() - 4))
                .doesNotContain(rawAccountReference);
        assertThat(comparison.matches()).isTrue();
        assertThat(comparison.mismatches()).isEmpty();
        assertThat(comparison.original().outcome()).isEqualTo(comparison.replayed().outcome());
        assertThat(comparison.original().riskScore()).isEqualTo(comparison.replayed().riskScore());
        assertThat(comparison.original().riskBand()).isEqualTo(comparison.replayed().riskBand());
        assertThat(comparison.original().reasons()).isEqualTo(comparison.replayed().reasons());
        assertThat(comparison.policyVersion()).isEqualTo(decision.policyVersion());
        assertThat(comparison.algorithmVersion()).isEqualTo(decision.algorithmVersion());

        assertThat(countRows("outbox.outbox_event")).isEqualTo(outboxBefore);
        assertThat(countRows("recovery.recovery_flow")).isEqualTo(recoveryBefore);
        assertThat(countRows("challenge.challenge_plan")).isEqualTo(challengeBefore);
    }

    @Test
    void returnsEmptyForAnUnknownButWellFormedDecisionReference() {
        assertThat(replayQuery.replay(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void rejectsMalformedReferencesWithoutExposingPersistenceDetails() {
        assertThatThrownBy(() -> replayQuery.replay("not-a-decision-reference"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("decisionReference must be a valid UUID");
    }

    private long countRows(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    private ProtectionDecisionResult decide(RiskSignals signals, String accountReference) {
        return protectionDecisionService.decide(new ProtectionDecisionCommand(
                accountReference,
                ProtectionEventType.LOGIN_ATTEMPT,
                new RiskSignalEnvelope(
                        signals,
                        "CLIENT_SUPPLIED",
                        Instant.now(),
                        SignalConfidence.HIGH,
                        null,
                        true),
                "replay-idem-" + UUID.randomUUID()));
    }
}
