package io.github.viniciusssantos.accountshield.policy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.policy.CreatePolicyCommand;
import io.github.viniciusssantos.accountshield.policy.DuplicatePolicyVersionException;
import io.github.viniciusssantos.accountshield.policy.IllegalPolicyTransitionException;
import io.github.viniciusssantos.accountshield.policy.PendingPolicyVersionExistsException;
import io.github.viniciusssantos.accountshield.policy.PolicyStatus;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionNotFoundException;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionSummary;
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

class PolicyLifecycleApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");
    private static final String POLICY_KEY = "account-protection-default";
    private static final String VERSION = "2.0.0";

    private final PolicyVersionRepository repository = mock(PolicyVersionRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final PolicyLifecycleApplicationService service =
            new PolicyLifecycleApplicationService(repository, clock, eventPublisher);

    @Test
    void createsDraftWithCorrectFields() {
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.empty());
        when(repository.findByPolicyKeyAndStatusIn(eq(POLICY_KEY), any()))
                .thenReturn(List.of());

        PolicyVersionSummary result = service.createDraft(
                new CreatePolicyCommand(POLICY_KEY, VERSION, (short) 30, (short) 70));

        ArgumentCaptor<PolicyVersionEntity> captor = ArgumentCaptor.forClass(PolicyVersionEntity.class);
        verify(repository).save(captor.capture());
        PolicyVersionEntity saved = captor.getValue();

        assertThat(saved.getStatus()).isEqualTo(PolicyStatus.DRAFT.name());
        assertThat(saved.getAllowMaxScore()).isEqualTo((short) 30);
        assertThat(saved.getStepUpMaxScore()).isEqualTo((short) 70);
        assertThat(saved.getActivatedAt()).isNull();
        assertThat(result.status()).isEqualTo(PolicyStatus.DRAFT);
    }

    @Test
    void validatesDraftPolicy() {
        PolicyVersionEntity entity = draftPolicy();
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(entity));

        PolicyVersionSummary result = service.validate(POLICY_KEY, VERSION);

        assertThat(result.status()).isEqualTo(PolicyStatus.VALIDATED);
    }

    @Test
    void activateRetiresPreviouslyActiveAndActivatesCandidate() {
        PolicyVersionEntity candidate = draftPolicy();
        candidate.transitionTo(PolicyStatus.VALIDATED.name(), NOW);
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

        PolicyVersionSummary result = service.activate(POLICY_KEY, VERSION);

        assertThat(currentActive.getStatus()).isEqualTo(PolicyStatus.RETIRED.name());
        assertThat(candidate.getStatus()).isEqualTo(PolicyStatus.ACTIVE.name());
        assertThat(candidate.getActivatedAt()).isEqualTo(NOW);
        assertThat(result.status()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    void activateSucceedsWhenNoPreviouslyActivePolicy() {
        PolicyVersionEntity candidate = draftPolicy();
        candidate.transitionTo(PolicyStatus.VALIDATED.name(), NOW);
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(candidate));
        when(repository.findByPolicyKeyAndStatus(POLICY_KEY, "ACTIVE"))
                .thenReturn(Optional.empty());

        PolicyVersionSummary result = service.activate(POLICY_KEY, VERSION);

        assertThat(candidate.getStatus()).isEqualTo(PolicyStatus.ACTIVE.name());
        assertThat(result.status()).isEqualTo(PolicyStatus.ACTIVE);
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

        PolicyVersionSummary result = service.retire(POLICY_KEY, VERSION);

        assertThat(result.status()).isEqualTo(PolicyStatus.RETIRED);
    }

    @Test
    void cannotActivateDraftDirectly() {
        PolicyVersionEntity entity = draftPolicy();
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.activate(POLICY_KEY, VERSION))
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

        assertThatThrownBy(() -> service.validate(POLICY_KEY, VERSION))
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
                new CreatePolicyCommand(POLICY_KEY, VERSION, (short) 30, (short) 70)))
                .isInstanceOf(DuplicatePolicyVersionException.class);
    }

    @Test
    void cannotCreateDraftWhenNonTerminalPolicyExists() {
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.empty());
        when(repository.findByPolicyKeyAndStatusIn(eq(POLICY_KEY), any()))
                .thenReturn(List.of(draftPolicy()));

        assertThatThrownBy(() -> service.createDraft(
                new CreatePolicyCommand(POLICY_KEY, VERSION, (short) 30, (short) 70)))
                .isInstanceOf(PendingPolicyVersionExistsException.class);
    }

    @Test
    void rejectsInvalidScoreThresholds() {
        assertThatThrownBy(() -> service.createDraft(
                new CreatePolicyCommand(POLICY_KEY, VERSION, (short) 70, (short) 30)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.createDraft(
                new CreatePolicyCommand(POLICY_KEY, VERSION, (short) -1, (short) 30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyNotFoundThrowsException() {
        when(repository.findByPolicyKeyAndVersion(POLICY_KEY, VERSION))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validate(POLICY_KEY, VERSION))
                .isInstanceOf(PolicyVersionNotFoundException.class);
    }

    private PolicyVersionEntity draftPolicy() {
        return new PolicyVersionEntity(
                UUID.randomUUID(), POLICY_KEY, VERSION, "DRAFT",
                "{\"allowMaxScore\":30,\"stepUpMaxScore\":70}",
                (short) 30, (short) 70,
                NOW, null);
    }
}
