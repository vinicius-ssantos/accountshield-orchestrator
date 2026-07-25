package io.github.viniciusssantos.accountshield.policy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.challenge.ChallengePlan;
import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.challenge.ChallengeUseRejectedException;
import io.github.viniciusssantos.accountshield.policy.CreatePolicyCommand;
import io.github.viniciusssantos.accountshield.policy.DuplicatePolicyVersionException;
import io.github.viniciusssantos.accountshield.policy.IllegalPolicyTransitionException;
import io.github.viniciusssantos.accountshield.policy.PendingPolicyVersionExistsException;
import io.github.viniciusssantos.accountshield.policy.PolicyAnalysisFailedException;
import io.github.viniciusssantos.accountshield.policy.PolicyAnalyzer;
import io.github.viniciusssantos.accountshield.policy.PolicyStatus;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionNotFoundException;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionSummary;
import io.github.viniciusssantos.accountshield.policy.PrivilegedPolicyActionAttempted;
import io.github.viniciusssantos.accountshield.policy.SelfApprovalNotAllowedException;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionEntity;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

class PolicyLifecycleApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");
    private static final String POLICY_KEY = "account-protection-default";
    private static final String VERSION = "2.0.0";
    private static final String ACTOR = "admin-alice";
    private static final String AUTHOR = "author-bob";
    private static final String APPROVER = "approver-carol";
    private static final UUID STEP_UP_CHALLENGE_ID = UUID.randomUUID();

    private final PolicyVersionRepository repository = mock(PolicyVersionRepository.class);
    private final ChallengeService challengeService = mock(ChallengeService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final PolicyAnalyzer policyAnalyzer = new PolicyAnalyzer();
    private final PolicyLifecycleApplicationService service = new PolicyLifecycleApplicationService(
            repository, challengeService, clock, eventPublisher, policyAnalyzer, new ObjectMapper());

    private void stubSuccessfulStepUp() {
        when(challengeService.consume(any())).thenReturn(consumedChallengePlan());
    }

    private void stubRejectedStepUp() {
        when(challengeService.consume(any())).thenThrow(new ChallengeUseRejectedException());
    }

    private ChallengePlan consumedChallengePlan() {
        return new ChallengePlan(
                STEP_UP_CHALLENGE_ID, ACTOR, ChallengeType.TOTP_SIMULATED, ChallengePurpose.PRIVILEGED_OPERATION,
                UUID.randomUUID(), ChallengeStatus.CONSUMED, 3, 3, NOW.minusSeconds(60), NOW.plusSeconds(600), NOW);
    }

    @Test
    void createsDraftWithCorrectFields() {
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.empty());
        when(repository.findByPolicyKeyAndStatusIn(eq(POLICY_KEY), any()))
                .thenReturn(List.of());

        PolicyVersionSummary result = service.createDraft(
                new CreatePolicyCommand(POLICY_KEY, VERSION, (short) 30, (short) 70), AUTHOR);

        ArgumentCaptor<PolicyVersionEntity> captor = ArgumentCaptor.forClass(PolicyVersionEntity.class);
        verify(repository).save(captor.capture());
        PolicyVersionEntity saved = captor.getValue();

        assertThat(saved.getStatus()).isEqualTo(PolicyStatus.DRAFT.name());
        assertThat(saved.getAllowMaxScore()).isEqualTo((short) 30);
        assertThat(saved.getStepUpMaxScore()).isEqualTo((short) 70);
        assertThat(saved.getActivatedAt()).isNull();
        assertThat(saved.getCreatedBy()).isEqualTo(AUTHOR);
        assertThat(result.status()).isEqualTo(PolicyStatus.DRAFT);
        assertThat(result.governance().createdBy()).isEqualTo(AUTHOR);
    }

    @Test
    void validatesDraftPolicy() {
        PolicyVersionEntity entity = draftPolicy();
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(entity));

        PolicyVersionSummary result = service.validate(POLICY_KEY, VERSION, ACTOR);

        assertThat(result.status()).isEqualTo(PolicyStatus.VALIDATED);
        assertThat(result.analysis()).isNotNull();
        assertThat(result.analysis().hasErrors()).isFalse();
        assertThat(entity.getAnalysis()).isNotNull();
        assertThat(entity.getValidatedBy()).isEqualTo(ACTOR);
        assertThat(entity.getValidatedAt()).isEqualTo(NOW);
    }

    @Test
    void validateRejectsPolicyWithMissingThresholdAndLeavesStatusAsDraft() {
        PolicyVersionEntity entity = new PolicyVersionEntity(
                UUID.randomUUID(), POLICY_KEY, VERSION, "DRAFT",
                "{\"allowMaxScore\":30,\"stepUpMaxScore\":70}",
                (short) 30, (short) 70,
                NOW, null);
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.validate(POLICY_KEY, VERSION, ACTOR))
                .isInstanceOf(PolicyAnalysisFailedException.class);

        assertThat(entity.getStatus()).isEqualTo("DRAFT");
        assertThat(entity.getAnalysis()).isNull();
    }

    @Test
    void validateRejectsShadowedStepUpBand() {
        PolicyVersionEntity entity = new PolicyVersionEntity(
                UUID.randomUUID(), POLICY_KEY, VERSION, "DRAFT",
                "{\"allowMaxScore\":70,\"stepUpMaxScore\":70,\"recoveryMaxScore\":89}",
                (short) 70, (short) 70, (short) 89,
                NOW, null);
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.validate(POLICY_KEY, VERSION, ACTOR))
                .isInstanceOf(PolicyAnalysisFailedException.class)
                .satisfies(ex -> assertThat(((PolicyAnalysisFailedException) ex).result().diagnostics())
                        .anyMatch(d -> d.code().equals("STEP_UP_BAND_SHADOWED")));
        assertThat(entity.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void approvesValidatedPolicyWhenApproverDiffersFromAuthor() {
        PolicyVersionEntity entity = validatedPolicy();
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(entity));
        stubSuccessfulStepUp();

        PolicyVersionSummary result = service.approve(
                POLICY_KEY, VERSION, STEP_UP_CHALLENGE_ID, APPROVER, "looks safe, approving rollout");

        assertThat(result.status()).isEqualTo(PolicyStatus.APPROVED);
        assertThat(entity.getApprovedBy()).isEqualTo(APPROVER);
        assertThat(entity.getApprovedAt()).isEqualTo(NOW);
        assertThat(entity.getApprovalReason()).isEqualTo("looks safe, approving rollout");
        assertThat(result.governance().approvedBy()).isEqualTo(APPROVER);
    }

    @Test
    void approveRejectsSelfApprovalAndLeavesStatusAsValidated() {
        PolicyVersionEntity entity = validatedPolicy();
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(entity));
        stubSuccessfulStepUp();

        assertThatThrownBy(() -> service.approve(
                POLICY_KEY, VERSION, STEP_UP_CHALLENGE_ID, AUTHOR, "self sign-off"))
                .isInstanceOf(SelfApprovalNotAllowedException.class);

        assertThat(entity.getStatus()).isEqualTo("VALIDATED");
        assertThat(entity.getApprovedBy()).isNull();
    }

    @Test
    void approveRejectsBlankReason() {
        PolicyVersionEntity entity = validatedPolicy();
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(entity));
        stubSuccessfulStepUp();

        assertThatThrownBy(() -> service.approve(POLICY_KEY, VERSION, STEP_UP_CHALLENGE_ID, APPROVER, "   "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(entity.getStatus()).isEqualTo("VALIDATED");
    }

    @Test
    void cannotApproveDraftDirectly() {
        PolicyVersionEntity entity = draftPolicy();
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(entity));
        stubSuccessfulStepUp();

        assertThatThrownBy(() -> service.approve(
                POLICY_KEY, VERSION, STEP_UP_CHALLENGE_ID, APPROVER, "approve"))
                .isInstanceOf(IllegalPolicyTransitionException.class);
    }

    @Test
    void activateRetiresPreviouslyActiveAndActivatesCandidate() {
        PolicyVersionEntity candidate = approvedPolicy();
        PolicyVersionEntity currentActive = new PolicyVersionEntity(
                UUID.randomUUID(), POLICY_KEY, "1.0.0", "ACTIVE",
                "{\"allowMaxScore\":29,\"stepUpMaxScore\":69}",
                (short) 29, (short) 69,
                Instant.parse("2026-07-20T00:00:00Z"),
                Instant.parse("2026-07-20T00:00:00Z"));
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(candidate));
        when(repository.findByPolicyKeyAndStatus(POLICY_KEY, "ACTIVE"))
                .thenReturn(Optional.of(currentActive));
        stubSuccessfulStepUp();

        PolicyVersionSummary result = service.activate(POLICY_KEY, VERSION, STEP_UP_CHALLENGE_ID, ACTOR);

        assertThat(currentActive.getStatus()).isEqualTo(PolicyStatus.RETIRED.name());
        assertThat(candidate.getStatus()).isEqualTo(PolicyStatus.ACTIVE.name());
        assertThat(candidate.getActivatedAt()).isEqualTo(NOW);
        assertThat(result.status()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    void activateSucceedsWhenNoPreviouslyActivePolicy() {
        PolicyVersionEntity candidate = approvedPolicy();
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(candidate));
        when(repository.findByPolicyKeyAndStatus(POLICY_KEY, "ACTIVE"))
                .thenReturn(Optional.empty());
        stubSuccessfulStepUp();

        PolicyVersionSummary result = service.activate(POLICY_KEY, VERSION, STEP_UP_CHALLENGE_ID, ACTOR);

        assertThat(candidate.getStatus()).isEqualTo(PolicyStatus.ACTIVE.name());
        assertThat(result.status()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    void activateFailsAndLeavesStateUnchangedWhenStepUpIsRejected() {
        PolicyVersionEntity candidate = approvedPolicy();
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(candidate));
        stubRejectedStepUp();

        assertThatThrownBy(() -> service.activate(POLICY_KEY, VERSION, STEP_UP_CHALLENGE_ID, ACTOR))
                .isInstanceOf(ChallengeUseRejectedException.class);

        assertThat(candidate.getStatus()).isEqualTo(PolicyStatus.APPROVED.name());
        ArgumentCaptor<PrivilegedPolicyActionAttempted> audit =
                ArgumentCaptor.forClass(PrivilegedPolicyActionAttempted.class);
        verify(eventPublisher).publishEvent(audit.capture());
        assertThat(audit.getValue().authorized()).isFalse();
        assertThat(audit.getValue().action()).isEqualTo("ACTIVATE");
    }

    @Test
    void cannotActivateMerelyValidatedPolicy() {
        PolicyVersionEntity entity = validatedPolicy();
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(entity));
        stubSuccessfulStepUp();

        assertThatThrownBy(() -> service.activate(POLICY_KEY, VERSION, STEP_UP_CHALLENGE_ID, ACTOR))
                .isInstanceOf(IllegalPolicyTransitionException.class);
    }

    @Test
    void rejectsDraftPolicy() {
        PolicyVersionEntity entity = draftPolicy();
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(entity));

        PolicyVersionSummary result = service.reject(POLICY_KEY, VERSION);

        assertThat(result.status()).isEqualTo(PolicyStatus.REJECTED);
    }

    @Test
    void rejectsValidatedPolicy() {
        PolicyVersionEntity entity = draftPolicy();
        entity.transitionTo(PolicyStatus.VALIDATED.name(), NOW);
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(entity));

        PolicyVersionSummary result = service.reject(POLICY_KEY, VERSION);

        assertThat(result.status()).isEqualTo(PolicyStatus.REJECTED);
    }

    @Test
    void retiresActivePolicy() {
        PolicyVersionEntity entity = new PolicyVersionEntity(
                UUID.randomUUID(), POLICY_KEY, VERSION, "ACTIVE",
                "{\"allowMaxScore\":30,\"stepUpMaxScore\":70}",
                (short) 30, (short) 70,
                NOW.minusSeconds(3600), NOW.minusSeconds(3600));
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(entity));
        stubSuccessfulStepUp();

        PolicyVersionSummary result = service.retire(POLICY_KEY, VERSION, STEP_UP_CHALLENGE_ID, ACTOR);

        assertThat(result.status()).isEqualTo(PolicyStatus.RETIRED);
    }

    @Test
    void retireFailsAndLeavesStateUnchangedWhenStepUpIsRejected() {
        PolicyVersionEntity entity = new PolicyVersionEntity(
                UUID.randomUUID(), POLICY_KEY, VERSION, "ACTIVE",
                "{\"allowMaxScore\":30,\"stepUpMaxScore\":70}",
                (short) 30, (short) 70,
                NOW.minusSeconds(3600), NOW.minusSeconds(3600));
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(entity));
        stubRejectedStepUp();

        assertThatThrownBy(() -> service.retire(POLICY_KEY, VERSION, STEP_UP_CHALLENGE_ID, ACTOR))
                .isInstanceOf(ChallengeUseRejectedException.class);

        assertThat(entity.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void cannotActivateDraftDirectly() {
        PolicyVersionEntity entity = draftPolicy();
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(entity));
        stubSuccessfulStepUp();

        assertThatThrownBy(() -> service.activate(POLICY_KEY, VERSION, STEP_UP_CHALLENGE_ID, ACTOR))
                .isInstanceOf(IllegalPolicyTransitionException.class);
    }

    @Test
    void cannotValidateActivePolicy() {
        PolicyVersionEntity entity = new PolicyVersionEntity(
                UUID.randomUUID(), POLICY_KEY, VERSION, "ACTIVE",
                "{\"allowMaxScore\":30,\"stepUpMaxScore\":70}",
                (short) 30, (short) 70,
                NOW.minusSeconds(3600), NOW.minusSeconds(3600));
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.validate(POLICY_KEY, VERSION, ACTOR))
                .isInstanceOf(IllegalPolicyTransitionException.class);
    }

    @Test
    void cannotRejectRetiredPolicy() {
        PolicyVersionEntity entity = new PolicyVersionEntity(
                UUID.randomUUID(), POLICY_KEY, VERSION, "RETIRED",
                "{\"allowMaxScore\":30,\"stepUpMaxScore\":70}",
                (short) 30, (short) 70,
                NOW.minusSeconds(3600), NOW.minusSeconds(3600));
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.reject(POLICY_KEY, VERSION))
                .isInstanceOf(IllegalPolicyTransitionException.class);
    }

    @Test
    void cannotCreateDraftWhenVersionAlreadyExists() {
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(draftPolicy()));

        assertThatThrownBy(() -> service.createDraft(
                new CreatePolicyCommand(POLICY_KEY, VERSION, (short) 30, (short) 70), AUTHOR))
                .isInstanceOf(DuplicatePolicyVersionException.class);
    }

    @Test
    void cannotCreateDraftWhenNonTerminalPolicyExists() {
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.empty());
        when(repository.findByPolicyKeyAndStatusIn(eq(POLICY_KEY), any()))
                .thenReturn(List.of(draftPolicy()));

        assertThatThrownBy(() -> service.createDraft(
                new CreatePolicyCommand(POLICY_KEY, VERSION, (short) 30, (short) 70), AUTHOR))
                .isInstanceOf(PendingPolicyVersionExistsException.class);
    }

    @Test
    void rejectsInvalidScoreThresholds() {
        assertThatThrownBy(() -> service.createDraft(
                new CreatePolicyCommand(POLICY_KEY, VERSION, (short) 70, (short) 30), AUTHOR))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.createDraft(
                new CreatePolicyCommand(POLICY_KEY, VERSION, (short) -1, (short) 30), AUTHOR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyNotFoundThrowsException() {
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validate(POLICY_KEY, VERSION, ACTOR))
                .isInstanceOf(PolicyVersionNotFoundException.class);
    }

    private PolicyVersionEntity draftPolicy() {
        PolicyVersionEntity entity = new PolicyVersionEntity(
                UUID.randomUUID(), POLICY_KEY, VERSION, "DRAFT",
                "{\"allowMaxScore\":30,\"stepUpMaxScore\":70,\"recoveryMaxScore\":89}",
                (short) 30, (short) 70, (short) 89,
                NOW, null);
        entity.setCreatedBy(AUTHOR);
        return entity;
    }

    private PolicyVersionEntity validatedPolicy() {
        PolicyVersionEntity entity = draftPolicy();
        entity.transitionTo(PolicyStatus.VALIDATED.name(), NOW);
        return entity;
    }

    private PolicyVersionEntity approvedPolicy() {
        PolicyVersionEntity entity = validatedPolicy();
        entity.transitionTo(PolicyStatus.APPROVED.name(), NOW);
        return entity;
    }
}
