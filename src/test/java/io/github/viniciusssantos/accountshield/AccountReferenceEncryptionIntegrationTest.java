package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.crypto.FieldEncryptionService;
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
class AccountReferenceEncryptionIntegrationTest {

    @Autowired
    private ProtectionDecisionService protectionDecisionService;

    @Autowired
    private FieldEncryptionService fieldEncryptionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void accountReferenceIsStoredEncryptedAndIrrecoverableAfterCryptoShredding() {
        String accountReference = "crypto-shred-target-" + UUID.randomUUID();
        ProtectionDecisionResult result = protectionDecisionService.decide(new ProtectionDecisionCommand(
                accountReference,
                ProtectionEventType.LOGIN_ATTEMPT,
                envelope(),
                "idem-" + UUID.randomUUID()));

        String storedValue = jdbcTemplate.queryForObject(
                "SELECT account_reference FROM protection.protection_request WHERE id = ?",
                String.class, result.protectionRequestId());

        assertThat(storedValue).isNotEqualTo(accountReference);
        assertThat(storedValue).startsWith("ENC:");
        assertThat(fieldEncryptionService.decrypt(storedValue)).isEqualTo(accountReference);

        fieldEncryptionService.shred(accountReference);

        assertThat(fieldEncryptionService.decrypt(storedValue)).isEqualTo(FieldEncryptionService.SHREDDED_MARKER);
        Long rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM protection.protection_request WHERE id = ?",
                Long.class, result.protectionRequestId());
        assertThat(rowCount).isEqualTo(1L);
    }

    private RiskSignalEnvelope envelope() {
        return new RiskSignalEnvelope(
                new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW),
                "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true);
    }
}
