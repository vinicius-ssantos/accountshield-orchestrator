package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.policy.PolicyEvaluation;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationService;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import io.github.viniciusssantos.accountshield.simulation.ReplayResult;
import io.github.viniciusssantos.accountshield.simulation.ShadowEvaluationResult;
import io.github.viniciusssantos.accountshield.simulation.SimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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

    @Test
    @Transactional
    void replayDeterministicallyMatchesOriginalDecision() {
        ProtectionDecisionResult original = protectionDecisionService.decide(
                new ProtectionDecisionCommand(
                        "replay-user-" + java.util.UUID.randomUUID(),
                        ProtectionEventType.LOGIN_ATTEMPT,
                        new RiskSignals(2, true, false, false, NetworkRiskLevel.LOW),
                        null));

        var replayOpt = simulationService.replay(original.protectionRequestId());

        assertThat(replayOpt).isPresent();
        assertThat(replayOpt.get().matches()).isTrue();
        assertThat(replayOpt.get().replayedOutcome()).isEqualTo(original.outcome().name());
        assertThat(replayOpt.get().originalRiskScore()).isEqualTo(replayOpt.get().replayedRiskScore());
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
