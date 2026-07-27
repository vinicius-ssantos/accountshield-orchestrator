package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.audit.AuditChainVerificationResult;
import io.github.viniciusssantos.accountshield.audit.AuditChainVerificationService;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
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
class AuditChainIntegrationTest {

    @Autowired
    private ProtectionDecisionService protectionDecisionService;

    @Autowired
    private AuditChainVerificationService auditChainVerificationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void consecutiveDecisionsFormAVerifiableChain() {
        // Scoped to only the range this test itself creates: other test classes sharing this
        // Testcontainers instance (AuditChainVerificationServiceTest) deliberately insert broken
        // chain links as part of their own coverage, so asserting the *entire* global chain is
        // clean would be flaky depending on test execution order.
        long before = currentMaxSequence();

        decide("chain-account-" + UUID.randomUUID());
        decide("chain-account-" + UUID.randomUUID());
        decide("chain-account-" + UUID.randomUUID());

        long after = currentMaxSequence();
        assertThat(after).isGreaterThanOrEqualTo(before + 3);

        AuditChainVerificationResult result = auditChainVerificationService.verifyRange(before + 1, after);

        assertThat(result.valid()).isTrue();
        assertThat(result.breaks()).isEmpty();
        assertThat(result.recordsChecked()).isEqualTo(after - before);
    }

    private long currentMaxSequence() {
        Long max = jdbcTemplate.queryForObject(
                "SELECT MAX(chain_sequence) FROM audit.decision_trace WHERE chain_sequence IS NOT NULL", Long.class);
        return max == null ? 0L : max;
    }

    @Test
    void everyChainedRowRecordsItsAlgorithmAndSchemaVersion() {
        decide("chain-account-" + UUID.randomUUID());

        Long unversionedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit.decision_trace "
                        + "WHERE chain_sequence IS NOT NULL "
                        + "AND (hash_algorithm IS NULL OR canonical_schema_version IS NULL OR record_hash IS NULL)",
                Long.class);
        assertThat(unversionedRows).isZero();
    }

    private void decide(String accountReference) {
        protectionDecisionService.decide(new ProtectionDecisionCommand(
                accountReference,
                ProtectionEventType.LOGIN_ATTEMPT,
                envelope(),
                "idem-" + UUID.randomUUID()));
    }

    private RiskSignalEnvelope envelope() {
        return new RiskSignalEnvelope(
                new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW),
                "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true);
    }
}
