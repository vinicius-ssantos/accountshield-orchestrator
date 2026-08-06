package io.github.viniciusssantos.accountshield.challenge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.PostgreSqlTestConfiguration;
import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.challenge.ChallengeUseRejectedException;
import io.github.viniciusssantos.accountshield.challenge.ChallengeVerificationCommand;
import io.github.viniciusssantos.accountshield.challenge.ConsumeChallengeCommand;
import io.github.viniciusssantos.accountshield.challenge.internal.persistence.ChallengePlanEntity;
import io.github.viniciusssantos.accountshield.challenge.internal.persistence.ChallengePlanRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Issue #39: "concurrent challenge verification and consumption." {@code
 * ChallengeApplicationService.consume} explicitly catches {@code OptimisticLockingFailureException}
 * (backed by {@code ChallengePlanEntity}'s {@code @Version} column) -- this proves that guarantee
 * directly against real Postgres: exactly one of N concurrent consumers wins, the rest are
 * rejected, never silently double-consumed.
 */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class ChallengeConcurrencyTest {

    private static final int CONTENDER_COUNT = 6;

    @Autowired
    private ChallengeService challengeService;

    @Autowired
    private ChallengePlanRepository challengePlanRepository;

    @Autowired
    private HmacChallengeCodeHasher codeHasher;

    @Test
    void concurrentConsumeOnTheSameVerifiedChallengeHasExactlyOneWinner() throws Exception {
        UUID challengeId = UUID.randomUUID();
        UUID contextId = UUID.randomUUID();
        String accountReference = "acct-consume-race-" + challengeId;
        Instant now = Instant.now();
        challengePlanRepository.save(new ChallengePlanEntity(
                challengeId, accountReference, ChallengeType.TOTP_SIMULATED.name(),
                ChallengePurpose.PROTECTION_STEP_UP.name(), contextId, ChallengeStatus.VERIFIED.name(),
                (short) 3, (short) 2, null, now, now.plusSeconds(600), null));

        List<Callable<Outcome>> contenders = new ArrayList<>();
        for (int i = 0; i < CONTENDER_COUNT; i++) {
            contenders.add(() -> {
                try {
                    challengeService.consume(new ConsumeChallengeCommand(
                            challengeId, accountReference, ChallengePurpose.PROTECTION_STEP_UP, contextId));
                    return Outcome.SUCCESS;
                } catch (ChallengeUseRejectedException exception) {
                    return Outcome.REJECTED;
                }
            });
        }

        List<Outcome> outcomes = race(contenders);

        assertThat(outcomes).filteredOn(o -> o == Outcome.SUCCESS).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o == Outcome.REJECTED).hasSize(CONTENDER_COUNT - 1);

        ChallengePlanEntity finalState = challengePlanRepository.findById(challengeId).orElseThrow();
        assertThat(finalState.getStatus()).isEqualTo(ChallengeStatus.CONSUMED.name());
        assertThat(finalState.getConsumedAt()).isNotNull();
    }

    @Test
    void concurrentVerifyWithTheCorrectCodeNeverCorruptsRemainingAttemptsOrDoubleTransitions()
            throws Exception {
        UUID challengeId = UUID.randomUUID();
        UUID contextId = UUID.randomUUID();
        String accountReference = "acct-verify-race-" + challengeId;
        String code = "654321";
        Instant now = Instant.now();
        short maxAttempts = 3;
        challengePlanRepository.save(new ChallengePlanEntity(
                challengeId, accountReference, ChallengeType.TOTP_SIMULATED.name(),
                ChallengePurpose.PROTECTION_STEP_UP.name(), contextId, ChallengeStatus.CHALLENGED.name(),
                maxAttempts, maxAttempts, codeHasher.hash(code), now, now.plusSeconds(600), null));

        List<Callable<Outcome>> contenders = new ArrayList<>();
        for (int i = 0; i < CONTENDER_COUNT; i++) {
            contenders.add(() -> {
                try {
                    challengeService.verify(new ChallengeVerificationCommand(
                            challengeId, code, ChallengePurpose.PROTECTION_STEP_UP, contextId));
                    return Outcome.SUCCESS;
                } catch (RuntimeException exception) {
                    // a concurrent writer losing the optimistic-lock race at commit is an
                    // acceptable, controlled outcome here -- this test's real assertion is on the
                    // final persisted state below, not on how many racers individually succeeded.
                    return Outcome.REJECTED;
                }
            });
        }

        race(contenders);

        ChallengePlanEntity finalState = challengePlanRepository.findById(challengeId).orElseThrow();
        assertThat(finalState.getStatus()).isEqualTo(ChallengeStatus.VERIFIED.name());
        // exactly one successful code check ever decremented remainingAttempts -- corruption
        // (e.g. a lost update letting two racers both decrement from the same stale value) would
        // show up here as a value other than maxAttempts - 1.
        assertThat(finalState.getRemainingAttempts()).isEqualTo((short) (maxAttempts - 1));
    }

    private enum Outcome {
        SUCCESS, REJECTED
    }

    private <T> List<T> race(List<Callable<T>> actions) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(actions.size());
        CountDownLatch ready = new CountDownLatch(actions.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> action : actions) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return action.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdown();
            executor.awaitTermination(20, TimeUnit.SECONDS);
        }
    }
}
