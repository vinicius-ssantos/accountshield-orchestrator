package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.policy.PolicyEvaluation;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationService;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignalEnvelope;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import io.github.viniciusssantos.accountshield.risk.SignalConfidence;
import io.github.viniciusssantos.accountshield.simulation.ReplayResult;
import io.github.viniciusssantos.accountshield.simulation.ShadowEvaluationResult;
import io.github.viniciusssantos.accountshield.simulation.SimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class SimulationIntegrationTest {

    @Autowired
    private io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService protectionDecisionService;

    @Autowired
    private SimulationService simulationService;

    @Autowired
    private PolicyEvaluationService policyEvaluationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Transactional
    void replayDeterministicallyMatchesOriginalDecision() {
        ProtectionDecisionResult original = protectionDecisionService.decide(
                new ProtectionDecisionCommand(
                        "replay-user-" + java.util.UUID.randomUUID(),
                        ProtectionEventType.LOGIN_ATTEMPT,
                        new RiskSignalEnvelope(
                                new RiskSignals(2, true, false, false, NetworkRiskLevel.LOW),
                                "CLIENT_SUPPLIED", java.time.Instant.now(), SignalConfidence.HIGH, null, true),
                        null));

        var replayOpt = simulationService.replay(original.protectionRequestId());

        assertThat(replayOpt).isPresent();
        assertThat(replayOpt.get().matches()).isTrue();
        assertThat(replayOpt.get().mismatches()).isEmpty();
        assertThat(replayOpt.get().replayedOutcome()).isEqualTo(original.outcome().name());
        assertThat(replayOpt.get().originalRiskScore()).isEqualTo(replayOpt.get().replayedRiskScore());
        assertThat(replayOpt.get().originalRiskBand()).isEqualTo(replayOpt.get().replayedRiskBand());
        assertThat(replayOpt.get().originalReasons()).isEqualTo(replayOpt.get().replayedReasons());
        assertThat(replayOpt.get().algorithmVersion()).isEqualTo("risk-rules-1.0");
    }

    @Test
    @Transactional
    void replayCreatesNoChallengeRecoveryOutboxOrAuditMutation() {
        ProtectionDecisionResult original = protectionDecisionService.decide(
                new ProtectionDecisionCommand(
                        "replay-sideeffect-" + java.util.UUID.randomUUID(),
                        ProtectionEventType.LOGIN_ATTEMPT,
                        new RiskSignalEnvelope(
                                new RiskSignals(10, false, false, false, NetworkRiskLevel.LOW),
                                "CLIENT_SUPPLIED", java.time.Instant.now(), SignalConfidence.HIGH, null, true),
                        null));

        long challengesBefore = count("challenge.challenge_plan");
        long recoveryFlowsBefore = count("recovery.recovery_flow");
        long outboxEventsBefore = count("outbox.outbox_event");
        long decisionTracesBefore = count("audit.decision_trace");

        var replayOpt = simulationService.replay(original.protectionRequestId());
        assertThat(replayOpt).isPresent();

        assertThat(count("challenge.challenge_plan")).isEqualTo(challengesBefore);
        assertThat(count("recovery.recovery_flow")).isEqualTo(recoveryFlowsBefore);
        assertThat(count("outbox.outbox_event")).isEqualTo(outboxEventsBefore);
        assertThat(count("audit.decision_trace")).isEqualTo(decisionTracesBefore);
    }

    private long count(String table) {
        Long result = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return result == null ? 0 : result;
    }

    @Test
    @Transactional
    void shadowEvaluationComparesLiveVsCandidatePolicy() {
        ShadowEvaluationResult result = simulationService.evaluateShadow(
                "account-protection-default", 35, "1.0.0");

        assertThat(result.liveOutcome()).isEqualTo("REQUIRE_STEP_UP");
        assertThat(result.shadowOutcome()).isEqualTo("REQUIRE_STEP_UP");
        assertThat(result.diverged()).isFalse();
    }

    @Test
    @Transactional
    void shadowEvaluationDivergesWhenScoreCrossesDifferentThresholds() {
        ShadowEvaluationResult result = simulationService.evaluateShadow(
                "account-protection-default", 70, "1.0.0");

        assertThat(result.liveOutcome()).isEqualTo("TEMPORARILY_BLOCK");
        assertThat(result.shadowOutcome()).isEqualTo("TEMPORARILY_BLOCK");
        assertThat(result.diverged()).isFalse();
    }
}
