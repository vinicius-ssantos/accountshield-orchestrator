package io.github.viniciusssantos.accountshield.challenge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.challenge.ChallengeResult;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.challenge.ChallengeVerificationCommand;
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

class ChallengeApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-20T03:00:00Z");

    private final ChallengePlanRepository repository = mock(ChallengePlanRepository.class);
    private final SimulatedChallengeProvider provider = new SimulatedChallengeProvider(java.util.random.RandomGenerator.getDefault());
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private ChallengeApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ChallengeApplicationService(repository, provider, clock);
    }

    @Test
    void createsChallengePlanInChallengedState() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var plan = service.create("account-1", ChallengeType.TOTP_SIMULATED);

        assertThat(plan.challengeId()).isNotNull();
        assertThat(plan.accountReference()).isEqualTo("account-1");
        assertThat(plan.challengeType()).isEqualTo(ChallengeType.TOTP_SIMULATED);
        assertThat(plan.status()).isEqualTo(ChallengeStatus.CHALLENGED);
        assertThat(plan.maxAttempts()).isEqualTo(3);
        assertThat(plan.remainingAttempts()).isEqualTo(3);
        assertThat(plan.createdAt()).isEqualTo(NOW);
        assertThat(plan.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    }

    @Test
    void verifiesCorrectCodeSuccessfully() {
        UUID challengeId = UUID.randomUUID();
        ChallengePlanEntity entity = activeEntity(challengeId, "123456");
        when(repository.findById(challengeId)).thenReturn(Optional.of(entity));

        ChallengeResult result = service.verify(new ChallengeVerificationCommand(challengeId, "123456"));

        assertThat(result.verified()).isTrue();
        assertThat(result.status()).isEqualTo(ChallengeStatus.VERIFIED);
        assertThat(result.challengeId()).isEqualTo(challengeId);
    }

    @Test
    void tracksRemainingAttemptsOnWrongCode() {
        UUID challengeId = UUID.randomUUID();
        ChallengePlanEntity entity = activeEntity(challengeId, "123456");
        when(repository.findById(challengeId)).thenReturn(Optional.of(entity));

        ChallengeResult result = service.verify(new ChallengeVerificationCommand(challengeId, "000000"));

        assertThat(result.verified()).isFalse();
        assertThat(result.status()).isEqualTo(ChallengeStatus.CHALLENGED);
        assertThat(result.remainingAttempts()).isEqualTo(2);
    }

    @Test
    void failsAfterMaxAttemptsExhausted() {
        UUID challengeId = UUID.randomUUID();
        ChallengePlanEntity entity = activeEntity(challengeId, "123456");
        when(repository.findById(challengeId)).thenReturn(Optional.of(entity));

        service.verify(new ChallengeVerificationCommand(challengeId, "wrong"));
        service.verify(new ChallengeVerificationCommand(challengeId, "wrong"));
        ChallengeResult result = service.verify(new ChallengeVerificationCommand(challengeId, "wrong"));

        assertThat(result.verified()).isFalse();
        assertThat(result.status()).isEqualTo(ChallengeStatus.FAILED);
        assertThat(result.remainingAttempts()).isEqualTo(0);
    }

    @Test
    void returnsVerifiedOnAlreadyVerifiedChallenge() {
        UUID challengeId = UUID.randomUUID();
        ChallengePlanEntity entity = activeEntity(challengeId, "123456");
        when(repository.findById(challengeId)).thenReturn(Optional.of(entity));

        service.verify(new ChallengeVerificationCommand(challengeId, "123456"));
        ChallengeResult result = service.verify(new ChallengeVerificationCommand(challengeId, "123456"));

        assertThat(result.verified()).isTrue();
        assertThat(result.status()).isEqualTo(ChallengeStatus.VERIFIED);
    }

    @Test
    void rejectsVerificationOnFailedChallenge() {
        UUID challengeId = UUID.randomUUID();
        ChallengePlanEntity entity = activeEntity(challengeId, "123456");
        when(repository.findById(challengeId)).thenReturn(Optional.of(entity));

        service.verify(new ChallengeVerificationCommand(challengeId, "wrong"));
        service.verify(new ChallengeVerificationCommand(challengeId, "wrong"));
        service.verify(new ChallengeVerificationCommand(challengeId, "wrong"));

        assertThatThrownBy(() -> service.verify(
                new ChallengeVerificationCommand(challengeId, "000000")))
                .isInstanceOf(InvalidChallengeStateException.class);
    }

    @Test
    void expiresChallengeAfterTtl() {
        UUID challengeId = UUID.randomUUID();
        Instant expired = NOW.minus(Duration.ofSeconds(1));
        ChallengePlanEntity entity = activeEntity(challengeId, "123456");
        entity.setStatus("CHALLENGED");
        when(repository.findById(challengeId)).thenReturn(Optional.of(entity));

        ChallengeApplicationService expiredService = new ChallengeApplicationService(
                repository, provider, Clock.fixed(NOW.plus(Duration.ofMinutes(11)), ZoneOffset.UTC));

        assertThatThrownBy(() -> expiredService.verify(
                new ChallengeVerificationCommand(challengeId, "000000")))
                .isInstanceOf(InvalidChallengeStateException.class);
    }

    @Test
    void rejectsNullInputs() {
        assertThatThrownBy(() -> service.create(null, ChallengeType.TOTP_SIMULATED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.create("a", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.verify(null))
                .isInstanceOf(NullPointerException.class);
    }

    private ChallengePlanEntity activeEntity(UUID id, String code) {
        return new ChallengePlanEntity(
                id, "account-1", "TOTP_SIMULATED", "CHALLENGED",
                (short) 3, (short) 3, code,
                NOW, NOW.plus(Duration.ofMinutes(10)));
    }
}