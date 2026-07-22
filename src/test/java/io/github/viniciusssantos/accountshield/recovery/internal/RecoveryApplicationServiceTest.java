package io.github.viniciusssantos.accountshield.recovery.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.challenge.ChallengePlan;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.recovery.ConfirmIdentityCommand;
import io.github.viniciusssantos.accountshield.recovery.InitiateRecoveryCommand;
import io.github.viniciusssantos.accountshield.recovery.InvalidRecoveryStateException;
import io.github.viniciusssantos.accountshield.recovery.RecoveryEventType;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlow;
import io.github.viniciusssantos.accountshield.recovery.RecoveryRiskClassification;
import io.github.viniciusssantos.accountshield.recovery.RecoveryReviewCommand;
import io.github.viniciusssantos.accountshield.recovery.RecoveryStatus;
import io.github.viniciusssantos.accountshield.recovery.internal.persistence.RecoveryFlowEntity;
import io.github.viniciusssantos.accountshield.recovery.internal.persistence.RecoveryFlowRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class RecoveryApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");

    private final RecoveryFlowRepository repository = mock(RecoveryFlowRepository.class);
    private final ChallengeService challengeService = mock(ChallengeService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private RecoveryApplicationService service;

    @BeforeEach
    void setUp() {
        service = new RecoveryApplicationService(repository, challengeService, clock, eventPublisher);
    }

    @Test
    void initiatesRecoveryWithIdentityChallengeAndClassifiesByRisk() {
        UUID challengeId = UUID.randomUUID();
        when(challengeService.create(any(), eq(ChallengeType.WEBAUTHN_SIMULATED)))
                .thenReturn(challengePlan(challengeId, ChallengeStatus.CHALLENGED));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RecoveryFlow flow = service.initiate(new InitiateRecoveryCommand(
                "user-123", RecoveryEventType.PASSWORD_RESET, 20));

        assertThat(flow.status()).isEqualTo(RecoveryStatus.VERIFYING_IDENTITY);
        assertThat(flow.classification()).isEqualTo(RecoveryRiskClassification.IMMEDIATE);
        assertThat(flow.identityChallengeId()).isEqualTo(challengeId);
        assertThat(flow.eligibleAfter()).isNull();
    }

    @Test
    void classifiesHighRiskAsManualReview() {
        when(challengeService.create(any(), any()))
                .thenReturn(challengePlan(UUID.randomUUID(), ChallengeStatus.CHALLENGED));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RecoveryFlow flow = service.initiate(new InitiateRecoveryCommand(
                "user-456", RecoveryEventType.LOGIN, 75));

        assertThat(flow.classification()).isEqualTo(RecoveryRiskClassification.MANUAL_REVIEW);
    }

    @Test
    void classifiesMediumRiskAsDelayedWithEligibleAfter() {
        when(challengeService.create(any(), any()))
                .thenReturn(challengePlan(UUID.randomUUID(), ChallengeStatus.CHALLENGED));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RecoveryFlow flow = service.initiate(new InitiateRecoveryCommand(
                "user-789", RecoveryEventType.CREDENTIAL_CHANGE, 45));

        assertThat(flow.classification()).isEqualTo(RecoveryRiskClassification.DELAYED);
        assertThat(flow.eligibleAfter()).isEqualTo(NOW.plusSeconds(900));
    }

    @Test
    void confirmIdentityTransitionsToVerifiedWhenChallengeIsVerified() {
        UUID recoveryId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        when(repository.findById(recoveryId)).thenReturn(Optional.of(
                entity(recoveryId, challengeId, RecoveryStatus.VERIFYING_IDENTITY,
                        RecoveryRiskClassification.IMMEDIATE)));
        when(challengeService.verifyIdentityForRecovery(challengeId))
                .thenReturn(challengePlan(challengeId, ChallengeStatus.VERIFIED));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RecoveryFlow flow = service.confirmIdentity(new ConfirmIdentityCommand(recoveryId, challengeId));

        assertThat(flow.status()).isEqualTo(RecoveryStatus.IDENTITY_VERIFIED);
    }

    @Test
    void confirmIdentityFailsWhenChallengeIsNotVerified() {
        UUID recoveryId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        when(repository.findById(recoveryId)).thenReturn(Optional.of(
                entity(recoveryId, challengeId, RecoveryStatus.VERIFYING_IDENTITY,
                        RecoveryRiskClassification.IMMEDIATE)));
        when(challengeService.verifyIdentityForRecovery(challengeId))
                .thenReturn(challengePlan(challengeId, ChallengeStatus.FAILED));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.confirmIdentity(
                new ConfirmIdentityCommand(recoveryId, challengeId)))
                .isInstanceOf(InvalidRecoveryStateException.class);
    }

    @Test
    void completeRequiresIdentityVerifiedOrDelayedState() {
        UUID recoveryId = UUID.randomUUID();
        when(repository.findById(recoveryId)).thenReturn(Optional.of(
                entity(recoveryId, UUID.randomUUID(), RecoveryStatus.INITIATED,
                        RecoveryRiskClassification.IMMEDIATE)));

        assertThatThrownBy(() -> service.complete(recoveryId))
                .isInstanceOf(InvalidRecoveryStateException.class);
    }

    @Test
    void completeRejectsManualReviewWithoutOperator() {
        UUID recoveryId = UUID.randomUUID();
        when(repository.findById(recoveryId)).thenReturn(Optional.of(
                entity(recoveryId, UUID.randomUUID(), RecoveryStatus.MANUAL_REVIEW,
                        RecoveryRiskClassification.MANUAL_REVIEW)));

        assertThatThrownBy(() -> service.complete(recoveryId))
                .isInstanceOf(InvalidRecoveryStateException.class);
    }

    @Test
    void completeRefusesDelayedRecoveryBeforeEligibleTime() {
        UUID recoveryId = UUID.randomUUID();
        RecoveryFlowEntity delayedEntity = entity(
                recoveryId, UUID.randomUUID(), RecoveryStatus.DELAYED,
                RecoveryRiskClassification.DELAYED);
        delayedEntity.setEligibleAfter(NOW.plusSeconds(600));
        when(repository.findById(recoveryId)).thenReturn(Optional.of(delayedEntity));

        assertThatThrownBy(() -> service.complete(recoveryId))
                .isInstanceOf(InvalidRecoveryStateException.class);
    }

    @Test
    void reviewApprovesManualRecovery() {
        UUID recoveryId = UUID.randomUUID();
        when(repository.findById(recoveryId)).thenReturn(Optional.of(
                entity(recoveryId, UUID.randomUUID(), RecoveryStatus.MANUAL_REVIEW,
                        RecoveryRiskClassification.MANUAL_REVIEW)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RecoveryFlow flow = service.review(new RecoveryReviewCommand(
                recoveryId, "APPROVE", "operator-alice"));

        assertThat(flow.status()).isEqualTo(RecoveryStatus.COMPLETED);
    }

    @Test
    void reviewRejectsManualRecovery() {
        UUID recoveryId = UUID.randomUUID();
        when(repository.findById(recoveryId)).thenReturn(Optional.of(
                entity(recoveryId, UUID.randomUUID(), RecoveryStatus.MANUAL_REVIEW,
                        RecoveryRiskClassification.MANUAL_REVIEW)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RecoveryFlow flow = service.review(new RecoveryReviewCommand(
                recoveryId, "REJECT", "operator-bob"));

        assertThat(flow.status()).isEqualTo(RecoveryStatus.REJECTED);
    }

    private ChallengePlan challengePlan(UUID challengeId, ChallengeStatus status) {
        return new ChallengePlan(
                challengeId, "user-ref", ChallengeType.WEBAUTHN_SIMULATED, status,
                3, 3, NOW, NOW.plusSeconds(600));
    }

    private RecoveryFlowEntity entity(
            UUID id, UUID challengeId, RecoveryStatus status,
            RecoveryRiskClassification classification) {
        return new RecoveryFlowEntity(
                id, "user-ref", "PASSWORD_RESET", status.name(),
                classification.name(), challengeId, 30,
                NOW, NOW, null, null);
    }
}
