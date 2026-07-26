package io.github.viniciusssantos.accountshield.policy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.challenge.ChallengePlan;
import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.challenge.ChallengeUseRejectedException;
import io.github.viniciusssantos.accountshield.policy.PolicyRollout;
import io.github.viniciusssantos.accountshield.policy.PolicyRolloutNotFoundException;
import io.github.viniciusssantos.accountshield.policy.PolicyRolloutStatus;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionNotFoundException;
import io.github.viniciusssantos.accountshield.policy.RolloutAlreadyActiveException;
import io.github.viniciusssantos.accountshield.policy.RolloutCandidateNotApprovedException;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyRolloutEntity;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyRolloutRepository;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionEntity;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class DatabasePolicyRolloutServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");
    private static final String POLICY_KEY = "account-protection-default";
    private static final String CANDIDATE_VERSION = "2.0.0";
    private static final String ACTOR = "admin-alice";
    private static final UUID STEP_UP_CHALLENGE_ID = UUID.randomUUID();

    private final PolicyRolloutRepository rolloutRepository = mock(PolicyRolloutRepository.class);
    private final PolicyVersionRepository versionRepository = mock(PolicyVersionRepository.class);
    private final ChallengeService challengeService = mock(ChallengeService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final DatabasePolicyRolloutService service = new DatabasePolicyRolloutService(
            rolloutRepository, versionRepository, challengeService, clock, eventPublisher);

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
    void startRolloutRequiresApprovedCandidate() {
        PolicyVersionEntity candidate = policyVersion("VALIDATED");
        when(versionRepository.findByPolicyKeyAndVersion(POLICY_KEY, CANDIDATE_VERSION))
                .thenReturn(Optional.of(candidate));
        stubSuccessfulStepUp();

        assertThatThrownBy(() -> service.startRollout(
                POLICY_KEY, CANDIDATE_VERSION, 10, STEP_UP_CHALLENGE_ID, ACTOR))
                .isInstanceOf(RolloutCandidateNotApprovedException.class);
    }

    @Test
    void startRolloutRejectsUnknownCandidate() {
        when(versionRepository.findByPolicyKeyAndVersion(POLICY_KEY, CANDIDATE_VERSION))
                .thenReturn(Optional.empty());
        stubSuccessfulStepUp();

        assertThatThrownBy(() -> service.startRollout(
                POLICY_KEY, CANDIDATE_VERSION, 10, STEP_UP_CHALLENGE_ID, ACTOR))
                .isInstanceOf(PolicyVersionNotFoundException.class);
    }

    @Test
    void startRolloutRejectsASecondConcurrentActiveRollout() {
        PolicyVersionEntity candidate = policyVersion("APPROVED");
        when(versionRepository.findByPolicyKeyAndVersion(POLICY_KEY, CANDIDATE_VERSION))
                .thenReturn(Optional.of(candidate));
        when(rolloutRepository.findByPolicyKeyAndStatus(POLICY_KEY, "ACTIVE"))
                .thenReturn(Optional.of(existingActiveRollout()));
        stubSuccessfulStepUp();

        assertThatThrownBy(() -> service.startRollout(
                POLICY_KEY, CANDIDATE_VERSION, 10, STEP_UP_CHALLENGE_ID, ACTOR))
                .isInstanceOf(RolloutAlreadyActiveException.class);
    }

    @Test
    void startRolloutSucceedsForApprovedCandidateWithNoConcurrentRollout() {
        PolicyVersionEntity candidate = policyVersion("APPROVED");
        when(versionRepository.findByPolicyKeyAndVersion(POLICY_KEY, CANDIDATE_VERSION))
                .thenReturn(Optional.of(candidate));
        when(rolloutRepository.findByPolicyKeyAndStatus(POLICY_KEY, "ACTIVE"))
                .thenReturn(Optional.empty());
        stubSuccessfulStepUp();

        PolicyRollout rollout = service.startRollout(
                POLICY_KEY, CANDIDATE_VERSION, 10, STEP_UP_CHALLENGE_ID, ACTOR);

        assertThat(rollout.policyKey()).isEqualTo(POLICY_KEY);
        assertThat(rollout.candidateVersion()).isEqualTo(CANDIDATE_VERSION);
        assertThat(rollout.rolloutPercentage()).isEqualTo(10);
        assertThat(rollout.status()).isEqualTo(PolicyRolloutStatus.ACTIVE);
        assertThat(rollout.startedBy()).isEqualTo(ACTOR);
    }

    @Test
    void startRolloutFailsAndCreatesNothingWhenStepUpIsRejected() {
        stubRejectedStepUp();

        assertThatThrownBy(() -> service.startRollout(
                POLICY_KEY, CANDIDATE_VERSION, 10, STEP_UP_CHALLENGE_ID, ACTOR))
                .isInstanceOf(ChallengeUseRejectedException.class);
    }

    @Test
    void updatePercentageRequiresAnExistingActiveRollout() {
        when(rolloutRepository.findByPolicyKeyAndStatus(POLICY_KEY, "ACTIVE"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePercentage(POLICY_KEY, 25, STEP_UP_CHALLENGE_ID, ACTOR))
                .isInstanceOf(PolicyRolloutNotFoundException.class);
    }

    @Test
    void updatePercentageChangesTheExistingRollout() {
        PolicyRolloutEntity entity = existingActiveRollout();
        when(rolloutRepository.findByPolicyKeyAndStatus(POLICY_KEY, "ACTIVE"))
                .thenReturn(Optional.of(entity));
        stubSuccessfulStepUp();

        PolicyRollout rollout = service.updatePercentage(POLICY_KEY, 40, STEP_UP_CHALLENGE_ID, ACTOR);

        assertThat(rollout.rolloutPercentage()).isEqualTo(40);
        assertThat(entity.getRolloutPercentage()).isEqualTo((short) 40);
    }

    @Test
    void rollbackRequiresNoStepUpAndIsImmediate() {
        PolicyRolloutEntity entity = existingActiveRollout();
        when(rolloutRepository.findByPolicyKeyAndStatus(POLICY_KEY, "ACTIVE"))
                .thenReturn(Optional.of(entity));

        PolicyRollout rollout = service.rollback(POLICY_KEY, ACTOR);

        assertThat(rollout.status()).isEqualTo(PolicyRolloutStatus.ROLLED_BACK);
        assertThat(entity.getStatus()).isEqualTo("ROLLED_BACK");
        assertThat(entity.getRolledBackBy()).isEqualTo(ACTOR);
        assertThat(entity.getRolledBackAt()).isEqualTo(NOW);
        org.mockito.Mockito.verifyNoInteractions(challengeService);
    }

    @Test
    void rollbackRequiresAnExistingActiveRollout() {
        when(rolloutRepository.findByPolicyKeyAndStatus(POLICY_KEY, "ACTIVE"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rollback(POLICY_KEY, ACTOR))
                .isInstanceOf(PolicyRolloutNotFoundException.class);
    }

    @Test
    void findActiveRolloutReturnsEmptyWhenNoRolloutExists() {
        when(rolloutRepository.findByPolicyKeyAndStatus(POLICY_KEY, "ACTIVE"))
                .thenReturn(Optional.empty());

        assertThat(service.findActiveRollout(POLICY_KEY)).isEmpty();
    }

    @Test
    void findActiveRolloutReturnsEmptyOnceCandidateHasBeenFullyActivated() {
        PolicyRolloutEntity entity = existingActiveRollout();
        when(rolloutRepository.findByPolicyKeyAndStatus(POLICY_KEY, "ACTIVE"))
                .thenReturn(Optional.of(entity));
        when(versionRepository.findByPolicyKeyAndVersion(POLICY_KEY, CANDIDATE_VERSION))
                .thenReturn(Optional.of(policyVersion("ACTIVE")));

        assertThat(service.findActiveRollout(POLICY_KEY)).isEmpty();
    }

    @Test
    void findActiveRolloutReturnsPresentWhenCandidateStillApproved() {
        PolicyRolloutEntity entity = existingActiveRollout();
        when(rolloutRepository.findByPolicyKeyAndStatus(POLICY_KEY, "ACTIVE"))
                .thenReturn(Optional.of(entity));
        when(versionRepository.findByPolicyKeyAndVersion(POLICY_KEY, CANDIDATE_VERSION))
                .thenReturn(Optional.of(policyVersion("APPROVED")));

        assertThat(service.findActiveRollout(POLICY_KEY)).isPresent();
    }

    private PolicyVersionEntity policyVersion(String status) {
        return new PolicyVersionEntity(
                UUID.randomUUID(), POLICY_KEY, CANDIDATE_VERSION, status,
                (short) 20, (short) 60, (short) 89, NOW, null);
    }

    private PolicyRolloutEntity existingActiveRollout() {
        return new PolicyRolloutEntity(
                UUID.randomUUID(), POLICY_KEY, CANDIDATE_VERSION, (short) 10, "ACTIVE", NOW, ACTOR, NOW);
    }
}
