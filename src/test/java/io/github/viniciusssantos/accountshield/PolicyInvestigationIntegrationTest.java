package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.challenge.ChallengeIssued;
import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeVerificationCommand;
import io.github.viniciusssantos.accountshield.investigation.PolicyInvestigationQuery;
import io.github.viniciusssantos.accountshield.investigation.PolicyInvestigationQuery.ImpactAvailability;
import io.github.viniciusssantos.accountshield.investigation.PolicyInvestigationQuery.PolicyInvestigationDetail;
import io.github.viniciusssantos.accountshield.policy.CreatePolicyCommand;
import io.github.viniciusssantos.accountshield.policy.PolicyDirectoryQuery;
import io.github.viniciusssantos.accountshield.policy.PolicyDirectoryQuery.PolicyLifecycleDetail;
import io.github.viniciusssantos.accountshield.policy.PolicyDirectoryQuery.PolicySummary;
import io.github.viniciusssantos.accountshield.policy.PolicyLifecycleService;
import io.github.viniciusssantos.accountshield.policy.PolicyRolloutService;
import io.github.viniciusssantos.accountshield.policy.PolicyStatus;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionSummary;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionRepository;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
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
class PolicyInvestigationIntegrationTest {

    private static final String AUTHOR = "policy-author-investigation-test";
    private static final String APPROVER = "policy-approver-investigation-test";
    private static final String SEEDED_POLICY_KEY = "account-protection-default";

    @Autowired private PolicyLifecycleService lifecycleService;
    @Autowired private PolicyRolloutService rolloutService;
    @Autowired private PolicyDirectoryQuery directoryQuery;
    @Autowired private PolicyInvestigationQuery investigationQuery;
    @Autowired private ProtectionDecisionService protectionDecisionService;
    @Autowired private ChallengeService challengeService;
    @Autowired private PolicyVersionRepository policyVersionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationEvents events;

    @Test
    @Transactional
    void searchListsThePolicyKeyWithLifecycleAndRolloutSummary() {
        String key = "search-policy-" + UUID.randomUUID();
        activateVersion(key, "1.0.0");

        PolicySummary summary = directoryQuery.search().stream()
                .filter(item -> item.policyKey().equals(key))
                .findFirst()
                .orElseThrow();

        assertThat(summary.totalVersions()).isEqualTo(1);
        assertThat(summary.activeVersion()).isEqualTo("1.0.0");
        assertThat(summary.activeVersionActivatedAt()).isNotNull();
        assertThat(summary.hasActiveRollout()).isFalse();
    }

    @Test
    @Transactional
    void investigateReturnsOrderedVersionHistoryWithGovernanceDiagnosticsAndRoutingScope() {
        String key = "history-policy-" + UUID.randomUUID();
        activateVersion(key, "1.0.0");
        draftAndValidate(key, "1.1.0");

        PolicyLifecycleDetail detail = directoryQuery.investigate(key).orElseThrow();

        assertThat(detail.versions()).hasSize(2);
        assertThat(detail.versions())
                .extracting(PolicyVersionSummary::version)
                .containsExactly("1.1.0", "1.0.0");
        PolicyVersionSummary active = detail.versions().stream()
                .filter(version -> version.status() == PolicyStatus.ACTIVE)
                .findFirst()
                .orElseThrow();
        assertThat(active.governance().createdBy()).isEqualTo(AUTHOR);
        assertThat(active.governance().approvedBy()).isEqualTo(APPROVER);
        PolicyVersionSummary validated = detail.versions().stream()
                .filter(version -> version.status() == PolicyStatus.VALIDATED)
                .findFirst()
                .orElseThrow();
        assertThat(validated.analysis()).isNotNull();
        assertThat(validated.analysis().analyzerVersion()).isNotBlank();
        assertThat(detail.routingScope()).isEmpty();
    }

    @Test
    @Transactional
    void investigateReturnsNotApplicableImpactWhenNoActiveRollout() {
        String key = "no-rollout-policy-" + UUID.randomUUID();
        activateVersion(key, "1.0.0");

        PolicyInvestigationDetail detail = investigationQuery.investigate(key).orElseThrow();

        assertThat(detail.activeRollout()).isNull();
        assertThat(detail.impactAnalysis()).isNull();
        assertThat(detail.impactAvailability()).isEqualTo(ImpactAvailability.NOT_APPLICABLE);
        assertThat(detail.versions()).hasSize(1);
    }

    @Test
    @Transactional
    void investigateComposesActiveRolloutAndMaskedImpactAnalysisWhenRolloutIsPresent() {
        String key = SEEDED_POLICY_KEY;
        decideUnderSeededPolicy("impact-subject-" + UUID.randomUUID());

        String candidateVersion = approvedCandidate(key, "canary-" + UUID.randomUUID().toString().substring(0, 8));
        UUID stepUpChallengeId = verifiedStepUp(
                rolloutService.requestRolloutStepUp(key, candidateVersion, "operator"));
        rolloutService.startRollout(key, candidateVersion, 100, stepUpChallengeId, "operator");

        PolicyInvestigationDetail detail = investigationQuery.investigate(key).orElseThrow();

        assertThat(detail.activeRollout()).isNotNull();
        assertThat(detail.activeRollout().candidateVersion()).isEqualTo(candidateVersion);
        assertThat(detail.activeRollout().rolloutPercentage()).isEqualTo(100);
        assertThat(detail.impactAvailability()).isEqualTo(ImpactAvailability.AVAILABLE);
        assertThat(detail.impactAnalysis()).isNotNull();
        assertThat(detail.impactAnalysis().candidatePolicyVersion()).isEqualTo(candidateVersion);
        assertThat(detail.impactAnalysis().totalDecisions()).isGreaterThanOrEqualTo(1);
        detail.impactAnalysis().divergentDecisions().forEach(decision -> {
            assertThat(decision.maskedProtectionRequestReference()).startsWith("••••");
            assertThat(decision.redactedAccountReference()).doesNotContain("impact-subject-");
        });
    }

    @Test
    @Transactional
    void rejectsMalformedReferencesWithoutExposingPersistenceDetails() {
        assertThatThrownBy(() -> directoryQuery.investigate(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> investigationQuery.investigate(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @Transactional
    void returnsEmptyForAnUnknownButWellFormedPolicyKey() {
        String unknownKey = "unknown-policy-" + UUID.randomUUID();
        assertThat(directoryQuery.investigate(unknownKey)).isEmpty();
        assertThat(investigationQuery.investigate(unknownKey)).isEmpty();
    }

    private void activateVersion(String key, String version) {
        lifecycleService.createDraft(
                new CreatePolicyCommand(key, version, (short) 25, (short) 65), AUTHOR);
        lifecycleService.validate(key, version, "policy-validator-investigation-test");
        approve(key, version);
        activate(key, version);
    }

    private void draftAndValidate(String key, String version) {
        lifecycleService.createDraft(
                new CreatePolicyCommand(key, version, (short) 25, (short) 65), AUTHOR);
        lifecycleService.validate(key, version, "policy-validator-investigation-test");
    }

    private String approvedCandidate(String key, String version) {
        draftAndValidate(key, version);
        UUID approvalStepUp = verifiedStepUp(
                lifecycleService.requestApprovalStepUp(key, version, APPROVER));
        lifecycleService.approve(key, version, approvalStepUp, APPROVER, "investigation test approval");
        return version;
    }

    private void approve(String key, String version) {
        UUID approvalStepUp = verifiedStepUp(
                lifecycleService.requestApprovalStepUp(key, version, APPROVER));
        lifecycleService.approve(key, version, approvalStepUp, APPROVER, "investigation test approval");
    }

    private void activate(String key, String version) {
        UUID activationStepUp = verifiedStepUp(
                lifecycleService.requestActivationStepUp(key, version, APPROVER));
        lifecycleService.activate(key, version, activationStepUp, APPROVER);
    }

    private void decideUnderSeededPolicy(String accountReference) {
        protectionDecisionService.decide(new ProtectionDecisionCommand(
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
