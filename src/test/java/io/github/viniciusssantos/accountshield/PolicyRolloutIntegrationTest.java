package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.challenge.ChallengeIssued;
import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeVerificationCommand;
import io.github.viniciusssantos.accountshield.policy.CohortAssignment;
import io.github.viniciusssantos.accountshield.policy.CreatePolicyCommand;
import io.github.viniciusssantos.accountshield.policy.PolicyLifecycleService;
import io.github.viniciusssantos.accountshield.policy.PolicyRollout;
import io.github.viniciusssantos.accountshield.policy.PolicyRolloutService;
import io.github.viniciusssantos.accountshield.policy.PolicyRolloutStatus;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionRepository;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
@RecordApplicationEvents
class PolicyRolloutIntegrationTest {

    private static final String POLICY_KEY = "account-protection-default";

    @Autowired
    private ProtectionDecisionService protectionDecisionService;

    @Autowired
    private PolicyRolloutService policyRolloutService;

    @Autowired
    private PolicyLifecycleService policyLifecycleService;

    @Autowired
    private ChallengeService challengeService;

    @Autowired
    private PolicyVersionRepository policyVersionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEvents events;

    @Test
    @Transactional
    void rolloutRoutesDecisionsToCandidateForSubjectsInCohortAndRecordsProvenance() {
        String candidateVersion = approvedCandidate();
        UUID stepUpChallengeId = verifiedStepUp(
                policyRolloutService.requestRolloutStepUp(POLICY_KEY, candidateVersion, "operator"));
        policyRolloutService.startRollout(POLICY_KEY, candidateVersion, 100, stepUpChallengeId, "operator");

        String accountReference = "rollout-subject-" + UUID.randomUUID();
        ProtectionDecisionResult result = decide(accountReference);

        assertThat(result.policyVersion()).isEqualTo(candidateVersion);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT normalized_context ->> 'rolloutCandidateSelected' "
                        + "FROM audit.decision_trace WHERE id = ?",
                String.class, result.decisionId()))
                .isEqualTo("true");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT normalized_context ->> 'rolloutCandidateVersion' "
                        + "FROM audit.decision_trace WHERE id = ?",
                String.class, result.decisionId()))
                .isEqualTo(candidateVersion);
    }

    @Test
    @Transactional
    void sameSubjectStaysInTheSameCohortAcrossRepeatedDecisions() {
        String accountReference = "rollout-stability-" + UUID.randomUUID();

        String candidateVersion = approvedCandidate();
        UUID stepUpChallengeId = verifiedStepUp(
                policyRolloutService.requestRolloutStepUp(POLICY_KEY, candidateVersion, "operator"));
        policyRolloutService.startRollout(POLICY_KEY, candidateVersion, 50, stepUpChallengeId, "operator");

        ProtectionDecisionResult first = decide(accountReference);
        ProtectionDecisionResult second = decide(accountReference);

        assertThat(first.policyVersion()).isEqualTo(second.policyVersion());
        assertThat(CohortAssignment.inCandidateCohort("default-client", accountReference, POLICY_KEY, 50))
                .isEqualTo(first.policyVersion().equals(candidateVersion));
    }

    @Test
    @Transactional
    void rollbackImmediatelyRoutesNewDecisionsBackToStable() {
        String candidateVersion = approvedCandidate();
        UUID stepUpChallengeId = verifiedStepUp(
                policyRolloutService.requestRolloutStepUp(POLICY_KEY, candidateVersion, "operator"));
        policyRolloutService.startRollout(POLICY_KEY, candidateVersion, 100, stepUpChallengeId, "operator");

        String accountReference = "rollout-rollback-" + UUID.randomUUID();
        ProtectionDecisionResult beforeRollback = decide(accountReference);
        assertThat(beforeRollback.policyVersion()).isEqualTo(candidateVersion);

        PolicyRollout rolledBack = policyRolloutService.rollback(POLICY_KEY, "operator");
        assertThat(rolledBack.status()).isEqualTo(PolicyRolloutStatus.ROLLED_BACK);

        ProtectionDecisionResult afterRollback = decide("rollout-rollback-after-" + UUID.randomUUID());
        assertThat(afterRollback.policyVersion()).isNotEqualTo(candidateVersion);
        assertThat(policyRolloutService.findActiveRollout(POLICY_KEY)).isEmpty();
    }

    private String approvedCandidate() {
        String candidateVersion = "canary-" + UUID.randomUUID().toString().substring(0, 8);
        policyLifecycleService.createDraft(
                new CreatePolicyCommand(POLICY_KEY, candidateVersion, (short) 5, (short) 50, (short) 80),
                "policy-author");
        policyLifecycleService.validate(POLICY_KEY, candidateVersion, "policy-author");
        UUID approvalStepUp = verifiedStepUp(
                policyLifecycleService.requestApprovalStepUp(POLICY_KEY, candidateVersion, "policy-approver").challengeId());
        policyLifecycleService.approve(
                POLICY_KEY, candidateVersion, approvalStepUp, "policy-approver", "approved for canary rollout");
        return candidateVersion;
    }

    private ProtectionDecisionResult decide(String accountReference) {
        return protectionDecisionService.decide(new ProtectionDecisionCommand(
                accountReference,
                ProtectionEventType.LOGIN_ATTEMPT,
                new RiskSignalEnvelope(
                        new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW),
                        "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true),
                null));
    }

    private UUID verifiedStepUp(UUID challengeId) {
        String issuedCode = events.stream(ChallengeIssued.class)
                .filter(event -> event.challengeId().equals(challengeId))
                .reduce((first, second) -> second)
                .orElseThrow()
                .issuedCode();
        challengeService.verify(new ChallengeVerificationCommand(
                challengeId, issuedCode, ChallengePurpose.PRIVILEGED_OPERATION,
                lookUpContextId(challengeId)));
        return challengeId;
    }

    private UUID lookUpContextId(UUID challengeId) {
        policyVersionRepository.flush();
        return jdbcTemplate.queryForObject(
                "SELECT context_id FROM challenge.challenge_plan WHERE id = ?",
                UUID.class,
                challengeId);
    }
}
