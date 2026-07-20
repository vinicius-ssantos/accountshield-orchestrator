package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.audit.DecisionTraceRecorder;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationService;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.protection.internal.ProtectionDecisionApplicationService;
import io.github.viniciusssantos.accountshield.protection.internal.persistence.ProtectionRequestRepository;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskAssessmentService;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import java.time.Clock;
import java.util.List;
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
class ProtectionDecisionIntegrationTest {

    @Autowired
    private ProtectionDecisionService protectionDecisionService;

    @Autowired
    private RiskAssessmentService riskAssessmentService;

    @Autowired
    private PolicyEvaluationService policyEvaluationService;

    @Autowired
    private ProtectionRequestRepository protectionRequestRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsAllInitialOutcomesWithVersionedExplainability() {
        List<ProtectionDecisionResult> decisions = List.of(
                decide("integration-allow-" + UUID.randomUUID(), new RiskSignals(
                        0, false, false, false, NetworkRiskLevel.LOW)),
                decide("integration-step-up-" + UUID.randomUUID(), new RiskSignals(
                        10, false, false, false, NetworkRiskLevel.LOW)),
                decide("integration-block-" + UUID.randomUUID(), new RiskSignals(
                        0, false, true, true, NetworkRiskLevel.LOW)));

        assertThat(decisions)
                .extracting(ProtectionDecisionResult::outcome)
                .containsExactly(
                        ProtectionOutcome.ALLOW,
                        ProtectionOutcome.REQUIRE_STEP_UP,
                        ProtectionOutcome.TEMPORARILY_BLOCK);
        assertThat(decisions)
                .extracting(ProtectionDecisionResult::riskScore)
                .containsExactly(0, 30, 75);

        for (ProtectionDecisionResult decision : decisions) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM protection.protection_request WHERE id = ?",
                    String.class,
                    decision.protectionRequestId()))
                    .isEqualTo("DECIDED");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT outcome FROM audit.decision_trace WHERE id = ?",
                    String.class,
                    decision.decisionId()))
                    .isEqualTo(decision.outcome().name());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT algorithm_version FROM audit.decision_trace WHERE id = ?",
                    String.class,
                    decision.decisionId()))
                    .isEqualTo("risk-rules-1.0");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT policy_key FROM audit.decision_trace WHERE id = ?",
                    String.class,
                    decision.decisionId()))
                    .isEqualTo("account-protection-default");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT policy_version FROM audit.decision_trace WHERE id = ?",
                    String.class,
                    decision.decisionId()))
                    .isEqualTo("1.0.0");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT normalized_context ->> 'networkRiskLevel' FROM audit.decision_trace WHERE id = ?",
                    String.class,
                    decision.decisionId()))
                    .isEqualTo("LOW");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit.decision_reason WHERE decision_id = ?",
                    Long.class,
                    decision.decisionId()))
                    .isEqualTo((long) decision.reasons().size());
        }
    }

    @Test
    void rollsBackTheProtectionRequestWhenAuditRecordingFailsAfterFlush() {
        String accountReference = "integration-rollback-" + UUID.randomUUID();
        long before = requestCount(accountReference);
        DecisionTraceRecorder failingRecorder = command -> {
            protectionRequestRepository.flush();
            throw new IllegalStateException("simulated audit persistence failure");
        };
        var failingService = new ProtectionDecisionApplicationService(
                riskAssessmentService,
                policyEvaluationService,
                protectionRequestRepository,
                failingRecorder,
                Clock.systemUTC());
        var command = new ProtectionDecisionCommand(
                accountReference,
                ProtectionEventType.LOGIN_ATTEMPT,
                new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW));
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> failingService.decide(command)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated audit persistence failure");

        assertThat(requestCount(accountReference)).isEqualTo(before);
    }

    private ProtectionDecisionResult decide(String accountReference, RiskSignals signals) {
        return protectionDecisionService.decide(new ProtectionDecisionCommand(
                accountReference,
                ProtectionEventType.LOGIN_ATTEMPT,
                signals));
    }

    private long requestCount(String accountReference) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM protection.protection_request WHERE account_reference = ?",
                Long.class,
                accountReference);
        return count == null ? 0 : count;
    }
}
