package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.recovery.InitiateRecoveryCommand;
import io.github.viniciusssantos.accountshield.recovery.RecoveryEventType;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlow;
import io.github.viniciusssantos.accountshield.recovery.RecoveryRiskClassification;
import io.github.viniciusssantos.accountshield.recovery.RecoveryService;
import io.github.viniciusssantos.accountshield.recovery.RecoveryStatus;
import io.github.viniciusssantos.accountshield.recovery.UnauthorizedRecoveryInitiationException;
import io.github.viniciusssantos.accountshield.recovery.internal.persistence.RecoveryFlowRepository;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import java.util.UUID;
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
    private ProtectionDecisionService protectionDecisionService;

    @Autowired
    private RecoveryFlowRepository recoveryFlowRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Transactional
    void initiatesRecoveryAndPersistsIdentityChallenge() {
        ProtectionDecisionResult decision = createDecision(
                new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW));

        RecoveryFlow flow = recoveryService.initiate(new InitiateRecoveryCommand(
                decision.protectionRequestId(), RecoveryEventType.PASSWORD_RESET));

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
        ProtectionDecisionResult decision = createDecision(
                new RiskSignals(0, false, true, true, NetworkRiskLevel.HIGH));

        RecoveryFlow flow = recoveryService.initiate(new InitiateRecoveryCommand(
                decision.protectionRequestId(), RecoveryEventType.LOGIN));

        assertThat(flow.classification()).isEqualTo(RecoveryRiskClassification.MANUAL_REVIEW);
    }

    @Test
    @Transactional
    void classifiesMediumRiskAsDelayedWithEligibleAfter() {
        ProtectionDecisionResult decision = createDecision(
                new RiskSignals(0, true, true, false, NetworkRiskLevel.LOW));

        RecoveryFlow flow = recoveryService.initiate(new InitiateRecoveryCommand(
                decision.protectionRequestId(), RecoveryEventType.CREDENTIAL_CHANGE));

        assertThat(flow.classification()).isEqualTo(RecoveryRiskClassification.DELAYED);
        assertThat(flow.eligibleAfter()).isNotNull();
    }

    @Test
    @Transactional
    void rejectsInitiationWhenProtectionRequestDoesNotExist() {
        assertThatThrownBy(() -> recoveryService.initiate(new InitiateRecoveryCommand(
                UUID.randomUUID(), RecoveryEventType.LOGIN)))
                .isInstanceOf(UnauthorizedRecoveryInitiationException.class);
    }

    private ProtectionDecisionResult createDecision(RiskSignals signals) {
        return protectionDecisionService.decide(new ProtectionDecisionCommand(
                "recovery-test-" + UUID.randomUUID(),
                ProtectionEventType.LOGIN_ATTEMPT,
                signals,
                "idem-" + UUID.randomUUID()));
    }
}
