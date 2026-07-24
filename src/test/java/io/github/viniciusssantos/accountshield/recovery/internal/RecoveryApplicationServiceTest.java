package io.github.viniciusssantos.accountshield.recovery.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.audit.DecisionTraceQuery;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceView;
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
import io.github.viniciusssantos.accountshield.recovery.RecoveryEventType;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlow;
import io.github.viniciusssantos.accountshield.recovery.RecoveryRiskClassification;
import io.github.viniciusssantos.accountshield.recovery.RecoveryReviewCommand;
import io.github.viniciusssantos.accountshield.recovery.RecoveryReviewDecision;
import io.github.viniciusssantos.accountshield.recovery.RecoveryStatus;
import io.github.viniciusssantos.accountshield.recovery.UnauthorizedRecoveryInitiationException;
import io.github.viniciusssantos.accountshield.recovery.internal.persistence.RecoveryFlowEntity;
import io.github.viniciusssantos.accountshield.recovery.internal.persistence.RecoveryFlowRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class RecoveryApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");

    private final RecoveryFlowRepository repository = mock(RecoveryFlowRepository.class);
    private final ChallengeService challengeService = mock(ChallengeService.class);
    private final DecisionTraceQuery decisionTraceQuery = mock(DecisionTraceQuery.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private RecoveryApplicationService service;

    @BeforeEach
    void setUp() {
        service = new RecoveryApplicationService(
                repository, challengeService, decisionTraceQuery, clock, eventPublisher);
    }

    @Test
    void initiatesRecoveryWithPurposeBoundIdentityChallenge() {
        UUID protectionRequestId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        when(decisionTraceQuery.findByProtectionRequestId(protectionRequestId))
                .thenReturn(Optional.of(trace(protectionRequestId, "user-123", 20)));
        stubChallengeCreation(challengeId);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RecoveryFlow flow = service.initiate(new InitiateRecoveryCommand(
                protectionRequestId, RecoveryEventType.PASSWORD_RESET));

        assertThat(flow.status()).isEqualTo(RecoveryStatus.VERIFYING_IDENTITY);
        assertThat(flow.classification()).isEqualTo(RecoveryRiskClassification.IMMEDIATE);
        assertThat(flow.identityChallengeId()).isEqualTo(challengeId);
        assertThat(flow.eligibleAfter()).isNull();
        assertThat(flow.protectionRequestId()).isEqualTo(protectionRequestId);

        ArgumentCaptor<CreateChallengeCommand> createCaptor =
                ArgumentCaptor.forClass(CreateChallengeCommand.class);
        verify(challengeService).create(createCaptor.capture());
        assertThat(createCaptor.getValue().accountReference()).isEqualTo("user-123");
        assertThat(createCaptor.getValue().purpose()).isEqualTo(ChallengePurpose.RECOVERY_IDENTITY);
        assertThat(createCaptor.getValue().contextId()).isEqualTo(flow.recoveryId());
    }

    @Test
    void rejectsInitiationWhenNoDecisionTraceExists() {
        UUID protectionRequestId = UUID.randomUUID();
        when(decisionTraceQuery.findByProtectionRequestId(protectionRequestId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.initiate(new InitiateRecoveryCommand(
                protectionRequestId, RecoveryEventType.LOGIN)))
                .isInstanceOf(UnauthorizedRecoveryInitiationException.class);
    }

    @Test
    void classifiesHighRiskAsManualReview() {
        UUID protectionRequestId = UUID.randomUUID();
        when(decisionTraceQuery.findByProtectionRequestId(protectionRequestId))
                .thenReturn(Optional.of(trace(protectionRequestId, "user-456", 75)));
        stubChallengeCreation(UUID.randomUUID());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RecoveryFlow flow = service.initiate(new InitiateRecoveryCommand(
                protectionRequestId, RecoveryEventType.LOGIN));

        assertThat(flow.classification()).isEqualTo(RecoveryRiskClassification.MANUAL_REVIEW);
    }

    @Test
    void classifiesMediumRiskAsDelayedWithEligibleAfter() {
        UUID protectionRequestId = UUID.randomUUID();
        when(decisionTraceQuery.findByProtectionRequestId(protectionRequestId))
                .thenReturn(Optional.of(trace(protectionRequestId, "user-789", 45)));
        stubChallengeCreation(UUID.randomUUID());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RecoveryFlow flow = service.initiate(new InitiateRecoveryCommand(
                protectionRequestId, RecoveryEventType.CREDENTIAL_CHANGE));

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
                        RecoveryRiskClassification.IMMEDIATE)));

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
                        RecoveryRiskClassification.IMMEDIATE)));
        when(challengeService.consume(any(ConsumeChallengeCommand.class)))
                .thenReturn(challengePlan(challengeId, ChallengeStatus.CONSUMED, recoveryId));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RecoveryFlow flow = service.confirmIdentity(new ConfirmIdentityCommand(recoveryId, challengeId));

        assertThat(flow.status()).isEqualTo(RecoveryStatus.IDENTITY_VERIFIED);
        ArgumentCaptor<ConsumeChallengeCommand> consumeCaptor =
                ArgumentCaptor.forClass(ConsumeChallengeCommand.class);
        verify(challengeService).consume(consumeCaptor.capture());
        assertThat(consumeCaptor.getValue().accountReference()).isEqualTo("user-ref");
        assertThat(consumeCaptor.getValue().purpose()).isEqualTo(ChallengePurpose.RECOVERY_IDENTITY);
        assertThat(consumeCaptor.getValue().contextId()).isEqualTo(recoveryId);
    }

    @Test
    void confirmIdentityFailsWhenChallengeIsNotVerified() {
        UUID recoveryId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        when(repository.findById(recoveryId)).thenReturn(Optional.of(
                entity(recoveryId, challengeId, RecoveryStatus.VERIFYING_IDENTITY,
                        RecoveryRiskClassification.IMMEDIATE)));
        when(challengeService.consume(any(ConsumeChallengeCommand.class)))
                .thenThrow(new InvalidChallengeStateException(challengeId, ChallengeStatus.FAILED));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.confirmIdentity(
                new ConfirmIdentityCommand(recoveryId, challengeId)))
                .isInstanceOf(InvalidRecoveryStateException.class);
    }

    @Test
    void confirmIdentityReturnsGenericUnauthorizedErrorWhenChallengeWasReused() {
        UUID recoveryId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        when(repository.findById(recoveryId)).thenReturn(Optional.of(
                entity(recoveryId, challengeId, RecoveryStatus.VERIFYING_IDENTITY,
                        RecoveryRiskClassification.IMMEDIATE)));
        when(challengeService.consume(any(ConsumeChallengeCommand.class)))
                .thenThrow(new ChallengeUseRejectedException());

        assertThatThrownBy(() -> service.confirmIdentity(
                new ConfirmIdentityCommand(recoveryId, challengeId)))
                .isInstanceOf(UnauthorizedRecoveryInitiationException.class)
                .hasMessage("challenge cannot authorize this recovery flow");
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
                recoveryId, RecoveryReviewDecision.APPROVE, "operator-alice"));

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
                recoveryId, RecoveryReviewDecision.REJECT, "operator-bob"));

        assertThat(flow.status()).isEqualTo(RecoveryStatus.REJECTED);
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

    private DecisionTraceView trace(UUID protectionRequestId, String accountRef, int riskScore) {
        return new DecisionTraceView(
                UUID.randomUUID(), protectionRequestId, accountRef, "fingerprint",
                "risk-rules-1.0", "account-protection-default", "1.0.0",
                "START_RECOVERY", riskScore,
                Map.of(
                        "protectionEventType", "PASSWORD_RESET_ATTEMPT",
                        "recoveryRequest", true),
                NOW, List.of());
    }

    private RecoveryFlowEntity entity(
            UUID id, UUID challengeId, RecoveryStatus status,
            RecoveryRiskClassification classification) {
        return new RecoveryFlowEntity(
                id, "user-ref", "PASSWORD_RESET", status.name(),
                classification.name(), challengeId, 30,
                NOW, NOW, null, null, UUID.randomUUID());
    }
}
