package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class OutboxEventIntegrationTest {

    @Autowired
    private ProtectionDecisionService protectionDecisionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void recordsProtectionDecisionEventInOutbox() {
        String accountReference = "outbox-test-" + UUID.randomUUID();

        ProtectionDecisionResult result = protectionDecisionService.decide(new ProtectionDecisionCommand(
                accountReference,
                ProtectionEventType.LOGIN_ATTEMPT,
                new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW),
                "idem-" + UUID.randomUUID()));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM outbox.outbox_event WHERE aggregate_id = ? AND event_type = 'PROTECTION_DECISION_MADE'",
                result.decisionId().toString());

        assertThat(row.get("aggregate_type")).isEqualTo("ProtectionDecision");
        assertThat(row.get("aggregate_id")).isEqualTo(result.decisionId().toString());
        assertThat(row.get("event_type")).isEqualTo("PROTECTION_DECISION_MADE");
        assertThat(row.get("occurred_at")).isNotNull();
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("attempt_count")).isEqualTo(0);
        assertThat(row.get("payload").toString()).doesNotContain(accountReference);
        assertThat(row.get("payload").toString()).contains("subjectToken");
    }

    @Test
    void sameAccountAlwaysProducesTheSameSubjectToken() {
        String accountReference = "outbox-pseudonym-" + UUID.randomUUID();

        ProtectionDecisionResult first = protectionDecisionService.decide(new ProtectionDecisionCommand(
                accountReference,
                ProtectionEventType.LOGIN_ATTEMPT,
                new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW),
                "idem-" + UUID.randomUUID()));
        ProtectionDecisionResult second = protectionDecisionService.decide(new ProtectionDecisionCommand(
                accountReference,
                ProtectionEventType.LOGIN_ATTEMPT,
                new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW),
                "idem-" + UUID.randomUUID()));

        String firstToken = subjectTokenOf(first.decisionId());
        String secondToken = subjectTokenOf(second.decisionId());

        assertThat(firstToken).isNotBlank().isEqualTo(secondToken);
    }

    private String subjectTokenOf(UUID decisionId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT payload FROM outbox.outbox_event WHERE aggregate_id = ? AND event_type = 'PROTECTION_DECISION_MADE'",
                decisionId.toString());
        String payload = row.get("payload").toString();
        int start = payload.indexOf("\"subjectToken\":\"") + "\"subjectToken\":\"".length();
        int end = payload.indexOf('"', start);
        return payload.substring(start, end);
    }

    @Test
    void eachDecisionProducesExactlyOneOutboxEntry() {
        ProtectionDecisionResult decision = protectionDecisionService.decide(new ProtectionDecisionCommand(
                "outbox-dedup-" + UUID.randomUUID(),
                ProtectionEventType.LOGIN_ATTEMPT,
                new RiskSignals(10, true, false, false, NetworkRiskLevel.LOW),
                "idem-" + UUID.randomUUID()));

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox.outbox_event WHERE aggregate_id = ?",
                Long.class,
                decision.decisionId().toString());
        assertThat(count).isEqualTo(1L);
    }
}
