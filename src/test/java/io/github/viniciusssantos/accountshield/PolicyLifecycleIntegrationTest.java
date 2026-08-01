package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.challenge.ChallengeIssued;
import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeVerificationCommand;
import io.github.viniciusssantos.accountshield.policy.CreatePolicyCommand;
import io.github.viniciusssantos.accountshield.policy.IllegalPolicyTransitionException;
import io.github.viniciusssantos.accountshield.policy.PolicyAnalysisFailedException;
import io.github.viniciusssantos.accountshield.policy.PolicyAnalysisResult;
import io.github.viniciusssantos.accountshield.policy.PolicyLifecycleService;
import io.github.viniciusssantos.accountshield.policy.PolicyStatus;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionSummary;
import io.github.viniciusssantos.accountshield.policy.SelfApprovalNotAllowedException;
import io.github.viniciusssantos.accountshield.policy.StepUpChallenge;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionRepository;
import jakarta.persistence.EntityManager;
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
class PolicyLifecycleIntegrationTest {

    private static final String ACTOR = "policy-admin-integration-test";
    private static final String AUTHOR = "policy-author-integration-test";
    private static final String APPROVER = "policy-approver-integration-test";

    @Autowired
    private PolicyLifecycleService lifecycleService;

    @Autowired
    private ChallengeService challengeService;

    @Autowired
    private PolicyVersionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEvents events;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    void createsDraftValidatesApprovesAndActivatesPolicy() {
        String key = "test-policy-" + java.util.UUID.randomUUID();
        String version = "1.0.0";

        PolicyVersionSummary draft = lifecycleService.createDraft(
                new CreatePolicyCommand(key, version, (short) 25, (short) 65), AUTHOR);
        assertThat(draft.status()).isEqualTo(PolicyStatus.DRAFT);

        PolicyVersionSummary validated = lifecycleService.validate(key, version, ACTOR);
        assertThat(validated.status()).isEqualTo(PolicyStatus.VALIDATED);

        PolicyVersionSummary approved = approve(key, version);
        assertThat(approved.status()).isEqualTo(PolicyStatus.APPROVED);

        PolicyVersionSummary activated = activate(key, version);
        assertThat(activated.status()).isEqualTo(PolicyStatus.ACTIVE);
        assertThat(activated.activatedAt()).isNotNull();

        repository.flush();

        String dbStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM policy.policy_version WHERE policy_key = ? AND version = ?",
                String.class, key, version);
        assertThat(dbStatus).isEqualTo("ACTIVE");

        Object[] governance = jdbcTemplate.queryForObject(
                "SELECT created_by, approved_by, approval_reason FROM policy.policy_version "
                        + "WHERE policy_key = ? AND version = ?",
                (rs, rowNum) -> new Object[] {
                        rs.getString("created_by"), rs.getString("approved_by"), rs.getString("approval_reason")},
                key, version);
        assertThat(governance[0]).isEqualTo(AUTHOR);
        assertThat(governance[1]).isEqualTo(APPROVER);
        assertThat(governance[2]).isEqualTo("integration test approval");
    }

    @Test
    @Transactional
    void approveRejectsSelfApprovalAndLeavesStatusAsValidated() {
        String key = "self-approve-policy-" + java.util.UUID.randomUUID();
        String version = "1.0.0";

        lifecycleService.createDraft(
                new CreatePolicyCommand(key, version, (short) 25, (short) 65), AUTHOR);
        lifecycleService.validate(key, version, ACTOR);

        UUID challengeId = verifiedStepUp(lifecycleService.requestApprovalStepUp(key, version, AUTHOR).challengeId());
        assertThatThrownBy(() -> lifecycleService.approve(key, version, challengeId, AUTHOR, "self sign-off"))
                .isInstanceOf(SelfApprovalNotAllowedException.class);

        repository.flush();
        String dbStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM policy.policy_version WHERE policy_key = ? AND version = ?",
                String.class, key, version);
        assertThat(dbStatus).isEqualTo("VALIDATED");
    }

    @Test
    @Transactional
    void approvalStepUpDisclosesTheSimulatedCodeMatchingTheIssuedEvent() {
        String key = "disclose-approve-policy-" + java.util.UUID.randomUUID();
        String version = "1.0.0";
        lifecycleService.createDraft(
                new CreatePolicyCommand(key, version, (short) 25, (short) 65), AUTHOR);
        lifecycleService.validate(key, version, ACTOR);

        StepUpChallenge stepUp = lifecycleService.requestApprovalStepUp(key, version, APPROVER);

        assertThat(stepUp.simulatedCode()).isEqualTo(issuedCodeFor(stepUp.challengeId()));
    }

    @Test
    @Transactional
    void activatingNewVersionRetiresPreviousActive() {
        String key = "swap-policy-" + java.util.UUID.randomUUID();

        PolicyVersionSummary v1 = createAndActivate(key, "1.0.0", (short) 20, (short) 60);
        PolicyVersionSummary v2 = createAndActivate(key, "2.0.0", (short) 25, (short) 65);

        assertThat(v2.status()).isEqualTo(PolicyStatus.ACTIVE);

        repository.flush();

        String v1Status = jdbcTemplate.queryForObject(
                "SELECT status FROM policy.policy_version WHERE policy_key = ? AND version = ?",
                String.class, key, "1.0.0");
        assertThat(v1Status).isEqualTo("RETIRED");

        String v2Status = jdbcTemplate.queryForObject(
                "SELECT status FROM policy.policy_version WHERE policy_key = ? AND version = ?",
                String.class, key, "2.0.0");
        assertThat(v2Status).isEqualTo("ACTIVE");
    }

    @Test
    @Transactional
    void cannotActivateDraftDirectly() {
        String key = "skip-policy-" + java.util.UUID.randomUUID();

        lifecycleService.createDraft(
                new CreatePolicyCommand(key, "1.0.0", (short) 25, (short) 65), AUTHOR);

        assertThatThrownBy(() -> activate(key, "1.0.0"))
                .isInstanceOf(IllegalPolicyTransitionException.class);
    }

    @Test
    @Transactional
    void cannotActivateMerelyValidatedPolicyWithoutApproval() {
        String key = "unapproved-policy-" + java.util.UUID.randomUUID();

        lifecycleService.createDraft(
                new CreatePolicyCommand(key, "1.0.0", (short) 25, (short) 65), AUTHOR);
        lifecycleService.validate(key, "1.0.0", ACTOR);

        assertThatThrownBy(() -> activate(key, "1.0.0"))
                .isInstanceOf(IllegalPolicyTransitionException.class);
    }

    @Test
    @Transactional
    void rejectTransitionsValidatedToRejected() {
        String key = "reject-policy-" + java.util.UUID.randomUUID();

        lifecycleService.createDraft(
                new CreatePolicyCommand(key, "1.0.0", (short) 25, (short) 65), AUTHOR);
        lifecycleService.validate(key, "1.0.0", ACTOR);
        PolicyVersionSummary result = lifecycleService.reject(key, "1.0.0");

        assertThat(result.status()).isEqualTo(PolicyStatus.REJECTED);
    }

    @Test
    @Transactional
    void retireTransitionsActiveToRetired() {
        String key = "retire-policy-" + java.util.UUID.randomUUID();

        createAndActivate(key, "1.0.0", (short) 20, (short) 60);
        PolicyVersionSummary result = retire(key, "1.0.0");

        assertThat(result.status()).isEqualTo(PolicyStatus.RETIRED);

        repository.flush();

        String dbStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM policy.policy_version WHERE policy_key = ? AND version = ?",
                String.class, key, "1.0.0");
        assertThat(dbStatus).isEqualTo("RETIRED");
    }

    @Test
    @Transactional
    void cannotTransitionFromTerminalState() {
        String key = "terminal-policy-" + java.util.UUID.randomUUID();

        createAndActivate(key, "1.0.0", (short) 20, (short) 60);
        retire(key, "1.0.0");

        assertThatThrownBy(() -> lifecycleService.validate(key, "1.0.0", ACTOR))
                .isInstanceOf(IllegalPolicyTransitionException.class);
        assertThatThrownBy(() -> approve(key, "1.0.0"))
                .isInstanceOf(IllegalPolicyTransitionException.class);
        assertThatThrownBy(() -> activate(key, "1.0.0"))
                .isInstanceOf(IllegalPolicyTransitionException.class);
        assertThatThrownBy(() -> lifecycleService.reject(key, "1.0.0"))
                .isInstanceOf(IllegalPolicyTransitionException.class);
    }

    @Test
    @Transactional
    void validatePersistsAnalysisAlongsideTheStatusTransition() {
        String key = "analysis-policy-" + java.util.UUID.randomUUID();
        String version = "1.0.0";
        lifecycleService.createDraft(new CreatePolicyCommand(key, version, (short) 25, (short) 65), AUTHOR);

        PolicyVersionSummary validated = lifecycleService.validate(key, version, ACTOR);

        assertThat(validated.status()).isEqualTo(PolicyStatus.VALIDATED);
        assertThat(validated.analysis()).isNotNull();
        assertThat(validated.analysis().hasErrors()).isFalse();
        assertThat(validated.analysis().analyzerVersion())
                .isEqualTo(PolicyAnalysisResult.CURRENT_ANALYZER_VERSION);

        repository.flush();
        String storedAnalysis = jdbcTemplate.queryForObject(
                "SELECT analysis::text FROM policy.policy_version WHERE policy_key = ? AND version = ?",
                String.class, key, version);
        assertThat(storedAnalysis).contains(PolicyAnalysisResult.CURRENT_ANALYZER_VERSION);
    }

    @Test
    @Transactional
    void validateRejectsAndLeavesDraftWhenAPersistedThresholdIsMissing() {
        String key = "broken-policy-" + java.util.UUID.randomUUID();
        String version = "1.0.0";
        lifecycleService.createDraft(new CreatePolicyCommand(key, version, (short) 25, (short) 65), AUTHOR);
        entityManager.flush();
        entityManager.clear();
        jdbcTemplate.update(
                "UPDATE policy.policy_version SET recovery_max_score = NULL, "
                        + "definition = definition - 'recoveryMaxScore' WHERE policy_key = ? AND version = ?",
                key, version);
        entityManager.clear();

        assertThatThrownBy(() -> lifecycleService.validate(key, version, ACTOR))
                .isInstanceOf(PolicyAnalysisFailedException.class);

        repository.flush();
        String dbStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM policy.policy_version WHERE policy_key = ? AND version = ?",
                String.class, key, version);
        assertThat(dbStatus).isEqualTo("DRAFT");
    }

    private PolicyVersionSummary createAndActivate(String key, String version, short allow, short stepUp) {
        lifecycleService.createDraft(new CreatePolicyCommand(key, version, allow, stepUp), AUTHOR);
        lifecycleService.validate(key, version, ACTOR);
        approve(key, version);
        return activate(key, version);
    }

    private PolicyVersionSummary approve(String key, String version) {
        UUID challengeId = verifiedStepUp(lifecycleService.requestApprovalStepUp(key, version, APPROVER).challengeId());
        return lifecycleService.approve(key, version, challengeId, APPROVER, "integration test approval");
    }

    private PolicyVersionSummary activate(String key, String version) {
        UUID challengeId = verifiedStepUp(lifecycleService.requestActivationStepUp(key, version, ACTOR).challengeId());
        return lifecycleService.activate(key, version, challengeId, ACTOR);
    }

    private PolicyVersionSummary retire(String key, String version) {
        UUID challengeId = verifiedStepUp(lifecycleService.requestRetirementStepUp(key, version, ACTOR).challengeId());
        return lifecycleService.retire(key, version, challengeId, ACTOR);
    }

    private UUID verifiedStepUp(UUID challengeId) {
        String issuedCode = issuedCodeFor(challengeId);
        challengeService.verify(new ChallengeVerificationCommand(
                challengeId, issuedCode, ChallengePurpose.PRIVILEGED_OPERATION,
                lookUpContextId(challengeId)));
        return challengeId;
    }

    private String issuedCodeFor(UUID challengeId) {
        return events.stream(ChallengeIssued.class)
                .filter(event -> event.challengeId().equals(challengeId))
                .reduce((first, second) -> second)
                .orElseThrow()
                .issuedCode();
    }

    private UUID lookUpContextId(UUID challengeId) {
        repository.flush();
        return jdbcTemplate.queryForObject(
                "SELECT context_id FROM challenge.challenge_plan WHERE id = ?",
                UUID.class,
                challengeId);
    }
}
