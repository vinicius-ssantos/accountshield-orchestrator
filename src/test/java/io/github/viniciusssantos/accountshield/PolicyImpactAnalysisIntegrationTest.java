package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.policy.CreatePolicyCommand;
import io.github.viniciusssantos.accountshield.policy.PolicyLifecycleService;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignalEnvelope;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import io.github.viniciusssantos.accountshield.risk.SignalConfidence;
import io.github.viniciusssantos.accountshield.simulation.PolicyImpactAnalysisService;
import io.github.viniciusssantos.accountshield.simulation.PolicyImpactReport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class PolicyImpactAnalysisIntegrationTest {

    private static final String POLICY_KEY = "account-protection-default";

    @Autowired
    private io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService protectionDecisionService;

    @Autowired
    private PolicyImpactAnalysisService policyImpactAnalysisService;

    @Autowired
    private PolicyLifecycleService policyLifecycleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Transactional
    void analyzeImpactDetectsDivergenceAgainstAStricterCandidateVersion() {
        String candidateVersion = "impact-" + UUID.randomUUID().toString().substring(0, 8);
        policyLifecycleService.createDraft(
                new CreatePolicyCommand(POLICY_KEY, candidateVersion, (short) 5, (short) 50, (short) 80),
                "policy-author");

        ProtectionDecisionResult decision = protectionDecisionService.decide(
                new ProtectionDecisionCommand(
                        "impact-user-" + UUID.randomUUID(),
                        ProtectionEventType.LOGIN_ATTEMPT,
                        new RiskSignalEnvelope(
                                new RiskSignals(2, true, false, false, NetworkRiskLevel.LOW),
                                "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true),
                        null));
        assertThat(decision.outcome().name()).isEqualTo("ALLOW");

        PolicyImpactReport report = policyImpactAnalysisService.analyzeImpact(POLICY_KEY, candidateVersion, 5000);

        assertThat(report.totalDecisions()).isGreaterThanOrEqualTo(1);
        assertThat(report.divergentDecisionsCount()).isGreaterThanOrEqualTo(1);
        assertThat(report.transitionMatrix().get("ALLOW")).isNotNull();
        assertThat(report.candidatePolicyVersion()).isEqualTo(candidateVersion);
        assertThat(report.algorithmVersionsObserved()).contains("risk-rules-1.0");
    }

    @Test
    @Transactional
    void analyzeImpactCreatesNoChallengeRecoveryOutboxOrAuditMutation() {
        String candidateVersion = "impact-se-" + UUID.randomUUID().toString().substring(0, 8);
        policyLifecycleService.createDraft(
                new CreatePolicyCommand(POLICY_KEY, candidateVersion, (short) 5, (short) 50, (short) 80),
                "policy-author");

        protectionDecisionService.decide(
                new ProtectionDecisionCommand(
                        "impact-sideeffect-user-" + UUID.randomUUID(),
                        ProtectionEventType.LOGIN_ATTEMPT,
                        new RiskSignalEnvelope(
                                new RiskSignals(2, true, false, false, NetworkRiskLevel.LOW),
                                "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true),
                        null));

        long challengesBefore = count("challenge.challenge_plan");
        long recoveryFlowsBefore = count("recovery.recovery_flow");
        long outboxEventsBefore = count("outbox.outbox_event");
        long decisionTracesBefore = count("audit.decision_trace");

        policyImpactAnalysisService.analyzeImpact(POLICY_KEY, candidateVersion, 5000);

        assertThat(count("challenge.challenge_plan")).isEqualTo(challengesBefore);
        assertThat(count("recovery.recovery_flow")).isEqualTo(recoveryFlowsBefore);
        assertThat(count("outbox.outbox_event")).isEqualTo(outboxEventsBefore);
        assertThat(count("audit.decision_trace")).isEqualTo(decisionTracesBefore);
    }

    private long count(String table) {
        Long result = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return result == null ? 0 : result;
    }
}
