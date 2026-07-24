package io.github.viniciusssantos.accountshield.recovery.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import io.github.viniciusssantos.accountshield.challenge.ConsumeChallengeCommand;
import io.github.viniciusssantos.accountshield.challenge.CreateChallengeCommand;
import io.github.viniciusssantos.accountshield.challenge.InvalidChallengeStateException;
import io.github.viniciusssantos.accountshield.recovery.ConfirmIdentityCommand;
import io.github.viniciusssantos.accountshield.recovery.InitiateRecoveryCommand;
import io.github.viniciusssantos.accountshield.recovery.InvalidRecoveryStateException;
import io.github.viniciusssantos.accountshield.recovery.RecoveryAuthorization;
import io.github.viniciusssantos.accountshield.recovery.RecoveryDirective;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlow;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowConflictException;
import io.github.viniciusssantos.accountshield.recovery.RecoveryRiskClassification;
import io.github.viniciusssantos.accountshield.recovery.RecoveryReviewCommand;
import io.github.viniciusssantos.accountshield.recovery.RecoveryReviewDecision;
import io.github.viniciusssantos.accountshield.recovery.RecoveryStatus;
import io.github.viniciusssantos.accountshield.recovery.UnauthorizedRecoveryInitiationException;
import io.github.viniciusssantos.accountshield.recovery.UnknownRecoveryClassificationRuleException;
import io.github.viniciusssantos.accountshield.recovery.internal.persistence.RecoveryFlowEntity;
import io.github.viniciusssantos.accountshield.recovery.internal.persistence.RecoveryFlowRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;

class RecoveryApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");

    private final RecoveryFlowRepository repository = mock(RecoveryFlowRepository.class);
    private final ChallengeService challengeService = mock(ChallengeService.class);
    private final RecoveryAuthorizationApplicationService authorizationService =
            mock(RecoveryAuthorizationApplicationService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private RecoveryApplicationService service;

    @BeforeEach
    void setUp() {
        service = new RecoveryApplicationService(
                repository, challengeService, authorizationService, clock, eventPublisher);
    }

    @Test
    void initiatesRecoveryFromPurposeBoundAuthorization() {
        UUID authorizationId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        RecoveryAuthorization authorization = authorization(
                authorizationId, "user-123", 20, RecoveryDirective.PASSWORD_RESET, NOW);
        when(repository.findByAuthorizationId(authorizationId)).thenReturn(Optional.empty());
        when(authorizationService.consume(authorizationId, NOW)).thenReturn(Optional.of(
                new RecoveryAuthorizationApplicationService.Consumption(authorization, true)));
        stubChallengeCreation(challengeId);

        RecoveryFlow flow = service.initiate(new InitiateRecoveryCommand(authorizationId));

        assertThat(flow.status()).isEqualTo(RecoveryStatus.VERIFYING_IDENTITY);
        assertThat(flow.classification()).isEqualTo(RecoveryRiskClassification.IMMEDIATE);
        assertThat(flow.identityChallengeId()).isEqualTo(challengeId);
        assertThat(flow.authorizationId()).isEqualTo(authorizationId);
        assertThat(flow.protectionRequestId()).isEqualTo(authorization.protectionRequestId());
        assertThat(flow.originatingDecisionId()).isEqualTo(authorization.decisionId());

        ArgumentCaptor<CreateChallengeCommand> createCaptor =
                ArgumentCaptor.forClass(CreateChallengeCommand.class);
        verify(challengeService).create(createCaptor.capture());
        assertThat(createCaptor.getValue().accountReference()).isEqualTo("user-123");
        assertThat(createCaptor.getValue().purpose()).isEqualTo(ChallengePurpose.RECOVERY_IDENTITY);
        assertThat(createCaptor.getValue().contextId()).isEqualTo(flow.recoveryId());
    }

    @Test
    void equivalentRetryReturnsExistingFlowWithoutConsumingAgain() {
        UUID authorizationId = UUID.randomUUID();
        RecoveryFlowEntity existing = entity(
                UUID.randomUUID(), UUID.randomUUID(), RecoveryStatus.VERIFYING_IDENTITY,
                RecoveryRiskClassification.DELAYED, authorizationId);
        when(repository.findByAuthorizationId(authorizationId)).thenReturn(Optional.of(existing));

        RecoveryFlow flow = service.initiate(new InitiateRecoveryCommand(authorizationId));

        assertThat(flow.recoveryId()).isEqualTo(existing.getId());
        assertThat(flow.authorizationId()).isEqualTo(authorizationId);
        verify(authorizationService, never()).consume(any(), any());
        verify(challengeService, never()).create(any());
    }

    @Test
    void rejectsMissingOrExpiredAuthorizationGenerically() {
        UUID authorizationId = UUID.randomUUID();
        when(repository.findByAuthorizationId(authorizationId)).thenReturn(Optional.empty());
        when(authorizationService.consume(authorizationId, NOW)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.initiate(new InitiateRecoveryCommand(authorizationId)))
                .isInstanceOf(UnauthorizedRecoveryInitiationException.class)
                .hasMessage("recovery authorization is invalid or unavailable");
    }

    @Test
    void rejectsConsumedAuthorizationWithoutConsistentFlow() {
        UUID authorizationId = UUID.randomUUID();
        RecoveryAuthorization authorization = authorization(
                authorizationId, "user-123", 45, RecoveryDirective.LOGIN, NOW.minusSeconds(30));
        when(repository.findByAuthorizationId(authorizationId)).thenReturn(Optional.empty());
        when(authorizationService.consume(authorizationId, NOW)).thenReturn(Optional.of(
                new RecoveryAuthorizationApplicationService.Consumption(authorization, false)));

        assertThatThrownBy(() -> service.initiate(new InitiateRecoveryCommand(authorizationId)))
                .isInstanceOf(UnauthorizedRecoveryInitiationException.class)
                .hasMessage("recovery authorization is invalid or unavailable");
    }

    @Test
    void classifiesHighRiskAsManualReview() {
        RecoveryFlow flow = initiateAtRisk(75);
        assertThat(flow.classification()).isEqualTo(RecoveryRiskClassification.MANUAL_REVIEW);
    }

    @Test
    void classifiesMediumRiskAsDelayedWithEligibleAfter() {
        RecoveryFlow flow = initiateAtRisk(45);
        assertThat(flow.classification()).isEqualTo(RecoveryRiskClassification.DELAYED);
        assertThat(flow.eligibleAfter()).isEqualTo(NOW.plusSeconds(900));
    }

    @Test
    void confirmIdentityRejectsChallengeFromDifferentRecoveryFlow() {
        UUID recoveryId = UUID.randomUUID();
        UUID correctChallengeId = UUID.randomUUID();
        UUID foreignChallengeId = UUID.randomUUID();
        when(repository.findById(recoveryId)).thenReturn(Optional.of(
                entity(recoveryId, correctChallengeId, RecoveryStatus.VERIFYING_IDENTITY,
                        RecoveryRiskClassification.IMMEDIATE, UUID.randomUUID())));

        assertThatThrownBy(() -> service.confirmIdentity(
                new ConfirmIdentityCommand(recoveryId, foreignChallengeId)))
                .isInstanceOf(UnauthorizedRecoveryInitiationException.class);
    }

    @Test
    void confirmIdentityConsumesMatchingVerifiedChallenge() {
        UUID recoveryId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        when(repository.findById(recoveryId)).thenReturn(Optional.of(
                entity(recoveryId, challengeId, RecoveryStatus.VERIFYING_IDENTITY,
                        RecoveryRiskClassification.IMMEDIATE, UUID.randomUUID())));
        when(challengeService.consume(any(ConsumeChallengeCommand.class)))
                .thenReturn(challengePlan(challengeId, ChallengeStatus.CONSUMED, recoveryId));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RecoveryFlow flow = service.confirmIdentity(new ConfirmIdentityCommand(recoveryId, challengeId));

        assertThat(flow.status()).isEqualTo(RecoveryStatus.IDENTITY_VERIFIED);
        assertThat(flow.classificationRuleVersion()).isEqualTo(RecoveryClassificationRule.VERSION);
        ArgumentCaptor<ConsumeChallengeCommand> consumeCaptor =
                ArgumentCaptor.forClass(ConsumeChallengeCommand.class);
        verify(challengeService).consume(consumeCaptor.capture());
        assertThat(consumeCaptor.getValue().accountReference()).isEqualTo("user-ref");
        assertThat(consumeCaptor.getValue().purpose()).isEqualTo(ChallengePurpose.RECOVERY_IDENTITY);
        assertThat(consumeCaptor.getValue().contextId()).isEqualTo(recoveryId);
    }

    @Test
    void confirmIdentityRejectsUnknownClassificationRuleVersion() {
        UUID recoveryId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        when(repository.findById(recoveryId)).thenReturn(Optional.of(
                entity(recoveryId, challengeId, RecoveryStatus.VERIFYING_IDENTITY,
                        RecoveryRiskClassification.IMMEDIATE, UUID.randomUUID(), "recovery-classification-0.9")));
        when(challengeService.consume(any(ConsumeChallengeCommand.class)))
                .thenReturn(challengePlan(challengeId, ChallengeStatus.CONSUMED, recoveryId));

        assertThatThrownBy(() -> service.confirmIdentity(new ConfirmIdentityCommand(recoveryId, challengeId)))
                .isInstanceOf(UnknownRecoveryClassificationRuleException.class);
    }

    @Test
    void confirmIdentityFailsWhenChallengeIsNotVerified() {
        UUID recoveryId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        when(repository.findById(recoveryId)).thenReturn(Optional.of(
                entity(recoveryId, challengeId, RecoveryStatus.VERIFYING_IDENTITY,
                        RecoveryRiskClassification.IMMEDIATE, UUID.randomUUID())));
        when(challengeService.consume(any(ConsumeChallengeCommand.class)))
                .thenThrow(new InvalidChallengeStateException(challengeId, ChallengeStatus.FAILED));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.confirmIdentity(
                new ConfirmIdentityCommand(recoveryId, challengeId)))
                .isInstanceOf(InvalidRecoveryStateException.class);
    }

    @Test
    void confirmIdentityTranslatesConcurrentModificationIntoConflict() {
        UUID recoveryId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        when(repository.findById(recoveryId)).thenReturn(Optional.of(
                entity(recoveryId, challengeId, RecoveryStatus.VERIFYING_IDENTITY,
                        RecoveryRiskClassification.IMMEDIATE, UUID.randomUUID())));
        when(challengeService.consume(any(ConsumeChallengeCommand.class)))
                .thenReturn(challengePlan(challengeId, ChallengeStatus.CONSUMED, recoveryId));
        when(repository.saveAndFlush(any())).thenThrow(new OptimisticLockingFailureException("stale"));

        assertThatThrownBy(() -> service.confirmIdentity(
                new ConfirmIdentityCommand(recoveryId, challengeId)))
                .isInstanceOf(RecoveryFlowConflictException.class);
    }

    @Test
    void confirmIdentityReturnsGenericUnauthorizedErrorWhenChallengeWasReused() {
        UUID recoveryId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        when(repository.findById(recoveryId)).thenReturn(Optional.of(
                entity(recoveryId, challengeId, RecoveryStatus.VERIFYING_IDENTITY,
                        RecoveryRiskClassification.IMMEDIATE, UUID.randomUUID())));
        when(challengeService.consume(any(ConsumeChallengeCommand.class)))
                .thenThrow(new ChallengeUseRejectedException());

        assertThatThrownBy(() -> service.confirmIdentity(
                new ConfirmIdentityCommand(recoveryId, challengeId)))
                .isInstanceOf(UnauthorizedRecoveryInitiationException.class)
                .hasMessage("challenge cannot authorize this recovery flow");
    }

    @Test
    void completeEnforcesClassificationState() {
        UUID recoveryId = UUID.randomUUID();
        when(repository.findById(recoveryId)).thenReturn(Optional.of(
                entity(recoveryId, UUID.randomUUID(), RecoveryStatus.MANUAL_REVIEW,
                        RecoveryRiskClassification.MANUAL_REVIEW, UUID.randomUUID())));

        assertThatThrownBy(() -> service.complete(recoveryId))
                .isInstanceOf(InvalidRecoveryStateException.class);
    }

    @Test
    void completeTranslatesConcurrentModificationIntoConflict() {
        UUID recoveryId = UUID.randomUUID();
        when(repository.findById(recoveryId)).thenReturn(Optional.of(
                entity(recoveryId, UUID.randomUUID(), RecoveryStatus.IDENTITY_VERIFIED,
                        RecoveryRiskClassification.IMMEDIATE, UUID.randomUUID())));
        when(repository.saveAndFlush(any())).thenThrow(new OptimisticLockingFailureException("stale"));

        assertThatThrownBy(() -> service.complete(recoveryId))
                .isInstanceOf(RecoveryFlowConflictException.class);
    }

    @Test
    void reviewApprovesManualRecovery() {
        UUID recoveryId = UUID.randomUUID();
        when(repository.findById(recoveryId)).thenReturn(Optional.of(
                entity(recoveryId, UUID.randomUUID(), RecoveryStatus.MANUAL_REVIEW,
                        RecoveryRiskClassification.MANUAL_REVIEW, UUID.randomUUID())));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RecoveryFlow flow = service.review(new RecoveryReviewCommand(
                recoveryId, RecoveryReviewDecision.APPROVE, "operator-alice"));

        assertThat(flow.status()).isEqualTo(RecoveryStatus.COMPLETED);
    }

    @Test
    void reviewTranslatesConcurrentModificationIntoConflict() {
        UUID recoveryId = UUID.randomUUID();
        when(repository.findById(recoveryId)).thenReturn(Optional.of(
                entity(recoveryId, UUID.randomUUID(), RecoveryStatus.MANUAL_REVIEW,
                        RecoveryRiskClassification.MANUAL_REVIEW, UUID.randomUUID())));
        when(repository.saveAndFlush(any())).thenThrow(new OptimisticLockingFailureException("stale"));

        assertThatThrownBy(() -> service.review(new RecoveryReviewCommand(
                recoveryId, RecoveryReviewDecision.APPROVE, "operator-alice")))
                .isInstanceOf(RecoveryFlowConflictException.class);
    }

    private RecoveryFlow initiateAtRisk(int riskScore) {
        UUID authorizationId = UUID.randomUUID();
        RecoveryAuthorization authorization = authorization(
                authorizationId, "user-risk", riskScore,
                RecoveryDirective.CREDENTIAL_CHANGE, NOW);
        when(repository.findByAuthorizationId(authorizationId)).thenReturn(Optional.empty());
        when(authorizationService.consume(authorizationId, NOW)).thenReturn(Optional.of(
                new RecoveryAuthorizationApplicationService.Consumption(authorization, true)));
        stubChallengeCreation(UUID.randomUUID());
        return service.initiate(new InitiateRecoveryCommand(authorizationId));
    }

    private RecoveryAuthorization authorization(
            UUID authorizationId,
            String accountReference,
            int riskScore,
            RecoveryDirective directive,
            Instant consumedAt) {
        return new RecoveryAuthorization(
                authorizationId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                accountReference,
                directive,
                riskScore,
                NOW.minusSeconds(60),
                NOW.plusSeconds(600),
                consumedAt);
    }

    private void stubChallengeCreation(UUID challengeId) {
        when(challengeService.create(any(CreateChallengeCommand.class)))
                .thenAnswer(invocation -> {
                    CreateChallengeCommand command = invocation.getArgument(0);
                    return challengePlan(challengeId, ChallengeStatus.CHALLENGED, command.contextId());
                });
    }

    private ChallengePlan challengePlan(
            UUID challengeId,
            ChallengeStatus status,
            UUID contextId) {
        return new ChallengePlan(
                challengeId,
                "user-ref",
                ChallengeType.WEBAUTHN_SIMULATED,
                ChallengePurpose.RECOVERY_IDENTITY,
                contextId,
                status,
                3,
                3,
                NOW,
                NOW.plusSeconds(600),
                status == ChallengeStatus.CONSUMED ? NOW : null);
    }

    private RecoveryFlowEntity entity(
            UUID id,
            UUID challengeId,
            RecoveryStatus status,
            RecoveryRiskClassification classification,
            UUID authorizationId) {
        return entity(id, challengeId, status, classification, authorizationId, RecoveryClassificationRule.VERSION);
    }

    private RecoveryFlowEntity entity(
            UUID id,
            UUID challengeId,
            RecoveryStatus status,
            RecoveryRiskClassification classification,
            UUID authorizationId,
            String classificationRuleVersion) {
        return new RecoveryFlowEntity(
                id,
                "user-ref",
                "PASSWORD_RESET",
                status.name(),
                classification.name(),
                classificationRuleVersion,
                challengeId,
                30,
                NOW,
                NOW,
                null,
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                authorizationId);
    }
}
