package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.protection.ConflictingIdempotencyRequestException;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.protection.internal.persistence.IdempotencyRecordRepository;
import io.github.viniciusssantos.accountshield.protection.internal.persistence.ProtectionRequestRepository;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class IdempotencyIntegrationTest {

    @Autowired
    private ProtectionDecisionService protectionDecisionService;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private ProtectionRequestRepository protectionRequestRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void returnsSameDecisionForIdenticalIdempotencyKey() {
        String idempotencyKey = "idem-dup-" + UUID.randomUUID();
        RiskSignals signals = new RiskSignals(3, false, false, false, NetworkRiskLevel.LOW);

        ProtectionDecisionResult first = protectionDecisionService.decide(new ProtectionDecisionCommand(
                "account-dup-" + UUID.randomUUID(),
                ProtectionEventType.LOGIN_ATTEMPT,
                signals,
                idempotencyKey));

        ProtectionDecisionResult second = protectionDecisionService.decide(new ProtectionDecisionCommand(
                "account-dup-" + UUID.randomUUID(),
                ProtectionEventType.LOGIN_ATTEMPT,
                signals,
                idempotencyKey));

        assertThat(second.decisionId()).isEqualTo(first.decisionId());
        assertThat(second.protectionRequestId()).isEqualTo(first.protectionRequestId());
        assertThat(second.outcome()).isEqualTo(first.outcome());
        assertThat(second.riskScore()).isEqualTo(first.riskScore());

        long requestCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM protection.protection_request WHERE id = ?",
                Long.class, first.protectionRequestId());
        assertThat(requestCount).isEqualTo(1);
    }

    @Test
    void rejectsConflictingIdempotencyKeyWithDifferentPayload() {
        String idempotencyKey = "idem-conflict-" + UUID.randomUUID();

        protectionDecisionService.decide(new ProtectionDecisionCommand(
                "account-conflict-" + UUID.randomUUID(),
                ProtectionEventType.LOGIN_ATTEMPT,
                new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW),
                idempotencyKey));

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                protectionDecisionService.decide(new ProtectionDecisionCommand(
                        "account-conflict-" + UUID.randomUUID(),
                        ProtectionEventType.LOGIN_ATTEMPT,
                        new RiskSignals(5, true, false, false, NetworkRiskLevel.LOW),
                        idempotencyKey))))
                .isInstanceOf(ConflictingIdempotencyRequestException.class);
    }

    @Test
    void createsDistinctDecisionsWhenNoIdempotencyKeyIsProvided() {
        String accountRef = "account-nokey-" + UUID.randomUUID();
        RiskSignals signals = new RiskSignals(2, false, false, false, NetworkRiskLevel.LOW);

        ProtectionDecisionResult first = protectionDecisionService.decide(new ProtectionDecisionCommand(
                accountRef,
                ProtectionEventType.LOGIN_ATTEMPT,
                signals,
                null));

        ProtectionDecisionResult second = protectionDecisionService.decide(new ProtectionDecisionCommand(
                accountRef,
                ProtectionEventType.LOGIN_ATTEMPT,
                signals,
                null));

        assertThat(second.decisionId()).isNotEqualTo(first.decisionId());
        assertThat(second.protectionRequestId()).isNotEqualTo(first.protectionRequestId());
    }

    @Test
    void persistsIdempotencyRecordWithCorrectFingerprintAndTtl() {
        String idempotencyKey = "idem-persist-" + UUID.randomUUID();

        ProtectionDecisionResult result = protectionDecisionService.decide(new ProtectionDecisionCommand(
                "account-persist-" + UUID.randomUUID(),
                ProtectionEventType.LOGIN_ATTEMPT,
                new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW),
                idempotencyKey));

        String fp = jdbcTemplate.queryForObject(
                "SELECT request_fingerprint FROM protection.protection_request WHERE id = ?",
                String.class, result.protectionRequestId());

        String storedFp = jdbcTemplate.queryForObject(
                "SELECT request_fingerprint FROM protection.idempotency_record WHERE idempotency_key = ?",
                String.class, idempotencyKey);

        assertThat(storedFp).isEqualTo(fp);

        String resourceId = jdbcTemplate.queryForObject(
                "SELECT resource_id::text FROM protection.idempotency_record WHERE idempotency_key = ?",
                String.class, idempotencyKey);
        assertThat(resourceId).isEqualTo(result.protectionRequestId().toString());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT resource_type FROM protection.idempotency_record WHERE idempotency_key = ?",
                String.class, idempotencyKey)).isEqualTo("protection_decision");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT response_payload FROM protection.idempotency_record WHERE idempotency_key = ?",
                String.class, idempotencyKey)).isNotNull();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_at < expires_at FROM protection.idempotency_record WHERE idempotency_key = ?",
                Boolean.class, idempotencyKey)).isTrue();
    }
}
