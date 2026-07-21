package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.recovery.InitiateRecoveryCommand;
import io.github.viniciusssantos.accountshield.recovery.RecoveryEventType;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlow;
import io.github.viniciusssantos.accountshield.recovery.RecoveryRiskClassification;
import io.github.viniciusssantos.accountshield.recovery.RecoveryService;
import io.github.viniciusssantos.accountshield.recovery.RecoveryStatus;
import io.github.viniciusssantos.accountshield.recovery.internal.persistence.RecoveryFlowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class RecoveryIntegrationTest {

    @Autowired
    private RecoveryService recoveryService;

    @Autowired
    private RecoveryFlowRepository recoveryFlowRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Transactional
    void initiatesRecoveryAndPersistsIdentityChallenge() {
        RecoveryFlow flow = recoveryService.initiate(new InitiateRecoveryCommand(
                "recovery-user-" + java.util.UUID.randomUUID(),
                RecoveryEventType.PASSWORD_RESET, 25));

        assertThat(flow.status()).isEqualTo(RecoveryStatus.VERIFYING_IDENTITY);
        assertThat(flow.classification()).isEqualTo(RecoveryRiskClassification.IMMEDIATE);
        assertThat(flow.identityChallengeId()).isNotNull();
        recoveryFlowRepository.flush();

        String persistedStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM recovery.recovery_flow WHERE id = ?",
                String.class, flow.recoveryId());
        assertThat(persistedStatus).isEqualTo("VERIFYING_IDENTITY");
    }

    @Test
    @Transactional
    void classifiesHighRiskAsManualReview() {
        RecoveryFlow flow = recoveryService.initiate(new InitiateRecoveryCommand(
                "high-risk-" + java.util.UUID.randomUUID(),
                RecoveryEventType.LOGIN, 85));

        assertThat(flow.classification()).isEqualTo(RecoveryRiskClassification.MANUAL_REVIEW);
    }

    @Test
    @Transactional
    void classifiesMediumRiskAsDelayedWithEligibleAfter() {
        RecoveryFlow flow = recoveryService.initiate(new InitiateRecoveryCommand(
                "medium-risk-" + java.util.UUID.randomUUID(),
                RecoveryEventType.CREDENTIAL_CHANGE, 50));

        assertThat(flow.classification()).isEqualTo(RecoveryRiskClassification.DELAYED);
        assertThat(flow.eligibleAfter()).isNotNull();
    }
}
