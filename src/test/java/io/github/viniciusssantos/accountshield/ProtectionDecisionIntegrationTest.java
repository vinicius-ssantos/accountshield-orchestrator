package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.audit.DecisionTraceRecorder;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationService;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.protection.IdempotencyGuard;
import io.github.viniciusssantos.accountshield.protection.IdempotencyResult;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

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
    private ChallengeService challengeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

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
            assertThat(jdbcTemplate.query(
                    "SELECT code FROM audit.decision_reason WHERE decision_id = ? ORDER BY ordinal",
                    (resultSet, rowNumber) -> resultSet.getString("code"),
                    decision.decisionId()))
                    .containsExactlyElementsOf(decision.reasons().stream()
                            .map(reason -> reason.code())
                            .toList());
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
        IdempotencyGuard noOpGuard = new IdempotencyGuard() {
            @Override
            public IdempotencyResult resolve(String key, String fingerprint, Instant now) {
                return IdempotencyResult.absent();
            }
            @Override
            public void record(String key, String fingerprint, String resourceType,
                    UUID resourceId, String responsePayload, Instant createdAt, Instant expiresAt) {
            }
        };
        var failingService = new ProtectionDecisionApplicationService(
                riskAssessmentService,
                policyEvaluationService,
                protectionRequestRepository,
                failingRecorder,
                noOpGuard,
                challengeService,
                Clock.systemUTC(),
                new ObjectMapper(),
                applicationContext);
        var command = new ProtectionDecisionCommand(
                accountReference,
                ProtectionEventType.LOGIN_ATTEMPT,
                new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW),
                "rollback-test-" + UUID.randomUUID());
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
                signals,
                "idem-" + UUID.randomUUID()));
    }

    private long requestCount(String accountReference) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM protection.protection_request WHERE account_reference = ?",
                Long.class,
                accountReference);
        return count == null ? 0 : count;
    }
}
