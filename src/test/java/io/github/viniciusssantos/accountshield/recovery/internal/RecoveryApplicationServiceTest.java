package io.github.viniciusssantos.accountshield.recovery.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.audit.DecisionTraceQuery;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceView;
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
    void initiatesRecoveryWithRiskScoreDerivedFromDecisionTrace() {
        UUID protectionRequestId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        when(decisionTraceQuery.findByProtectionRequestId(protectionRequestId))
                .thenReturn(Optional.of(trace(protectionRequestId, "user-123", 20)));
        when(challengeService.create(any(), eq(ChallengeType.WEBAUTHN_SIMULATED)))
                .thenReturn(challengePlan(challengeId, ChallengeStatus.CHALLENGED));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RecoveryFlow flow = service.initiate(new InitiateRecoveryCommand(
                protectionRequestId, RecoveryEventType.PASSWORD_RESET));

        assertThat(flow.status()).isEqualTo(RecoveryStatus.VERIFYING_IDENTITY);
        assertThat(flow.classification()).isEqualTo(RecoveryRiskClassification.IMMEDIATE);
        assertThat(flow.identityChallengeId()).isEqualTo(challengeId);
        assertThat(flow.eligibleAfter()).isNull();
        assertThat(flow.protectionRequestId()).isEqualTo(protectionRequestId);
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
        when(challengeService.create(any(), any()))
                .thenReturn(challengePlan(UUID.randomUUID(), ChallengeStatus.CHALLENGED));
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
        when(challengeService.create(any(), any()))
                .thenReturn(challengePlan(UUID.randomUUID(), ChallengeStatus.CHALLENGED));
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
    void confirmIdentityTransitionsToVerifiedWhenChallengeMatchesAndIsVerified() {
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
    void confirmIdentityFailsWhenChallengeMatchesButIsNotVerified() {
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

    private ChallengePlan challengePlan(UUID challengeId, ChallengeStatus status) {
        return new ChallengePlan(
                challengeId, "user-ref", ChallengeType.WEBAUTHN_SIMULATED, status,
                3, 3, NOW, NOW.plusSeconds(600));
    }

    private DecisionTraceView trace(UUID protectionRequestId, String accountRef, int riskScore) {
        return new DecisionTraceView(
                UUID.randomUUID(), protectionRequestId, accountRef, "fingerprint",
                "risk-rules-1.0", "account-protection-default", "1.0.0",
                "REQUIRE_STEP_UP", riskScore,
                Map.of(), NOW, List.of());
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
