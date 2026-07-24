package io.github.viniciusssantos.accountshield.challenge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.challenge.ChallengeIssued;
import io.github.viniciusssantos.accountshield.challenge.ChallengePlan;
import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeResult;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.challenge.ChallengeUseRejectedException;
import io.github.viniciusssantos.accountshield.challenge.ChallengeVerificationCommand;
import io.github.viniciusssantos.accountshield.challenge.ConsumeChallengeCommand;
import io.github.viniciusssantos.accountshield.challenge.CreateChallengeCommand;
import io.github.viniciusssantos.accountshield.challenge.InvalidChallengeStateException;
import io.github.viniciusssantos.accountshield.challenge.internal.persistence.ChallengePlanEntity;
import io.github.viniciusssantos.accountshield.challenge.internal.persistence.ChallengePlanRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;

class ChallengeApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-20T03:00:00Z");
    private static final UUID CONTEXT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private final ChallengePlanRepository repository = mock(ChallengePlanRepository.class);
    private final ChallengeCodecRegistry codecRegistry =
            new ChallengeCodecRegistry(new NumericCodeCodec(), new WebAuthnAssertionCodec());
    private final HmacChallengeCodeHasher codeHasher = new HmacChallengeCodeHasher("test-secret");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private ChallengeApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ChallengeApplicationService(repository, codecRegistry, codeHasher, clock, eventPublisher);
    }

    @Test
    void createsPurposeBoundChallengePlan() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ChallengePlan plan = service.create(new CreateChallengeCommand(
                "account-1",
                ChallengeType.TOTP_SIMULATED,
                ChallengePurpose.PROTECTION_STEP_UP,
                CONTEXT_ID));

        assertThat(plan.challengeId()).isNotNull();
        assertThat(plan.accountReference()).isEqualTo("account-1");
        assertThat(plan.challengeType()).isEqualTo(ChallengeType.TOTP_SIMULATED);
        assertThat(plan.purpose()).isEqualTo(ChallengePurpose.PROTECTION_STEP_UP);
        assertThat(plan.contextId()).isEqualTo(CONTEXT_ID);
        assertThat(plan.status()).isEqualTo(ChallengeStatus.CHALLENGED);
        assertThat(plan.maxAttempts()).isEqualTo(3);
        assertThat(plan.remainingAttempts()).isEqualTo(3);
        assertThat(plan.createdAt()).isEqualTo(NOW);
        assertThat(plan.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(plan.consumedAt()).isNull();
    }

    @Test
    void createPublishesTheIssuedCodeExactlyOnceAndNeverPersistsItRaw() {
        ArgumentCaptor<ChallengePlanEntity> savedEntity = ArgumentCaptor.forClass(ChallengePlanEntity.class);
        when(repository.save(savedEntity.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(new CreateChallengeCommand(
                "account-1", ChallengeType.TOTP_SIMULATED, ChallengePurpose.PROTECTION_STEP_UP, CONTEXT_ID));

        ArgumentCaptor<ChallengeIssued> issued = ArgumentCaptor.forClass(ChallengeIssued.class);
        verify(eventPublisher).publishEvent(issued.capture());

        String issuedCode = issued.getValue().issuedCode();
        assertThat(issuedCode).matches("\\d{6}");
        assertThat(savedEntity.getValue().getCodeHash()).isNotEqualTo(issuedCode);
        assertThat(savedEntity.getValue().getCodeHash()).isEqualTo(codeHasher.hash(issuedCode));
    }

    @Test
    void verifiesCorrectCodeForMatchingPurposeAndContext() {
        UUID challengeId = UUID.randomUUID();
        ChallengePlanEntity entity = activeEntity(challengeId, "123456", ChallengeStatus.CHALLENGED);
        when(repository.findById(challengeId)).thenReturn(Optional.of(entity));

        ChallengeResult result = service.verify(verification(challengeId, "123456"));

        assertThat(result.verified()).isTrue();
        assertThat(result.status()).isEqualTo(ChallengeStatus.VERIFIED);
        assertThat(result.challengeId()).isEqualTo(challengeId);
    }

    @Test
    void rejectsPurposeOrContextMismatchWithoutRevealingState() {
        UUID challengeId = UUID.randomUUID();
        ChallengePlanEntity entity = activeEntity(challengeId, "123456", ChallengeStatus.CHALLENGED);
        when(repository.findById(challengeId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.verify(new ChallengeVerificationCommand(
                challengeId,
                "123456",
                ChallengePurpose.RECOVERY_IDENTITY,
                CONTEXT_ID)))
                .isInstanceOf(ChallengeUseRejectedException.class)
                .hasMessage("challenge cannot be used for the requested operation");
        assertThat(entity.getRemainingAttempts()).isEqualTo((short) 3);
    }

    @Test
    void tracksRemainingAttemptsOnWrongCode() {
        UUID challengeId = UUID.randomUUID();
        ChallengePlanEntity entity = activeEntity(challengeId, "123456", ChallengeStatus.CHALLENGED);
        when(repository.findById(challengeId)).thenReturn(Optional.of(entity));

        ChallengeResult result = service.verify(verification(challengeId, "000000"));

        assertThat(result.verified()).isFalse();
        assertThat(result.status()).isEqualTo(ChallengeStatus.CHALLENGED);
        assertThat(result.remainingAttempts()).isEqualTo(2);
    }

    @Test
    void failsAfterMaxAttemptsExhausted() {
        UUID challengeId = UUID.randomUUID();
        ChallengePlanEntity entity = activeEntity(challengeId, "123456", ChallengeStatus.CHALLENGED);
        when(repository.findById(challengeId)).thenReturn(Optional.of(entity));

        service.verify(verification(challengeId, "wrong"));
        service.verify(verification(challengeId, "wrong"));
        ChallengeResult result = service.verify(verification(challengeId, "wrong"));

        assertThat(result.verified()).isFalse();
        assertThat(result.status()).isEqualTo(ChallengeStatus.FAILED);
        assertThat(result.remainingAttempts()).isEqualTo(0);
    }

    @Test
    void returnsVerifiedOnEquivalentRetryBeforeConsumption() {
        UUID challengeId = UUID.randomUUID();
        ChallengePlanEntity entity = activeEntity(challengeId, "123456", ChallengeStatus.CHALLENGED);
        when(repository.findById(challengeId)).thenReturn(Optional.of(entity));

        service.verify(verification(challengeId, "123456"));
        ChallengeResult result = service.verify(verification(challengeId, "123456"));

        assertThat(result.verified()).isTrue();
        assertThat(result.status()).isEqualTo(ChallengeStatus.VERIFIED);
    }

    @Test
    void consumesVerifiedChallengeExactlyOnce() {
        UUID challengeId = UUID.randomUUID();
        ChallengePlanEntity entity = activeEntity(challengeId, "123456", ChallengeStatus.VERIFIED);
        when(repository.findById(challengeId)).thenReturn(Optional.of(entity));

        ChallengePlan consumed = service.consume(consumption(challengeId));

        assertThat(consumed.status()).isEqualTo(ChallengeStatus.CONSUMED);
        assertThat(consumed.consumedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> service.consume(consumption(challengeId)))
                .isInstanceOf(ChallengeUseRejectedException.class);
    }

    @Test
    void convertsOptimisticLockConflictIntoGenericRejection() {
        UUID challengeId = UUID.randomUUID();
        ChallengePlanEntity entity = activeEntity(challengeId, "123456", ChallengeStatus.VERIFIED);
        when(repository.findById(challengeId)).thenReturn(Optional.of(entity));
        when(repository.saveAndFlush(any()))
                .thenThrow(new OptimisticLockingFailureException("concurrent consumption"));

        assertThatThrownBy(() -> service.consume(consumption(challengeId)))
                .isInstanceOf(ChallengeUseRejectedException.class);
    }

    @Test
    void rejectsVerificationOnFailedChallenge() {
        UUID challengeId = UUID.randomUUID();
        ChallengePlanEntity entity = activeEntity(challengeId, "123456", ChallengeStatus.FAILED);
        when(repository.findById(challengeId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.verify(verification(challengeId, "000000")))
                .isInstanceOf(InvalidChallengeStateException.class);
    }

    @Test
    void expiresChallengeAfterTtl() {
        UUID challengeId = UUID.randomUUID();
        ChallengePlanEntity entity = activeEntity(challengeId, "123456", ChallengeStatus.CHALLENGED);
        when(repository.findById(challengeId)).thenReturn(Optional.of(entity));

        ChallengeApplicationService expiredService = new ChallengeApplicationService(
                repository,
                codecRegistry,
                codeHasher,
                Clock.fixed(NOW.plus(Duration.ofMinutes(11)), ZoneOffset.UTC),
                eventPublisher);

        assertThatThrownBy(() -> expiredService.verify(verification(challengeId, "000000")))
                .isInstanceOf(InvalidChallengeStateException.class);
    }

    @Test
    void rejectsNullInputs() {
        assertThatThrownBy(() -> service.create(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.verify(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.consume(null)).isInstanceOf(NullPointerException.class);
    }

    private ChallengeVerificationCommand verification(UUID challengeId, String code) {
        return new ChallengeVerificationCommand(
                challengeId,
                code,
                ChallengePurpose.PROTECTION_STEP_UP,
                CONTEXT_ID);
    }

    private ConsumeChallengeCommand consumption(UUID challengeId) {
        return new ConsumeChallengeCommand(
                challengeId,
                "account-1",
                ChallengePurpose.PROTECTION_STEP_UP,
                CONTEXT_ID);
    }

    private ChallengePlanEntity activeEntity(UUID id, String code, ChallengeStatus status) {
        return new ChallengePlanEntity(
                id,
                "account-1",
                ChallengeType.TOTP_SIMULATED.name(),
                ChallengePurpose.PROTECTION_STEP_UP.name(),
                CONTEXT_ID,
                status.name(),
                (short) 3,
                (short) 3,
                codeHasher.hash(code),
                NOW,
                NOW.plus(Duration.ofMinutes(10)),
                null);
    }
}
