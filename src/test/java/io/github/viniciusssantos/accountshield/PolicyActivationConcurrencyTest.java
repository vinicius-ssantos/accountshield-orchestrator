package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.challenge.ChallengeIssued;
import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeVerificationCommand;
import io.github.viniciusssantos.accountshield.policy.IllegalPolicyTransitionException;
import io.github.viniciusssantos.accountshield.policy.PolicyLifecycleService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
@RecordApplicationEvents
class PolicyActivationConcurrencyTest {

    private static final int CONTENDER_COUNT = 2;
    private static final String ACTOR = "policy-admin-concurrency-test";

    @Autowired
    private PolicyLifecycleService lifecycleService;

    @Autowired
    private ChallengeService challengeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEvents events;

    @Test
    void concurrentActivationOfTwoApprovedVersionsHasExactlyOneWinner() throws Exception {
        // createDraft()'s "only one DRAFT/VALIDATED/APPROVED version per key" guard means two
        // versions can never both reach APPROVED through the normal API at the same time; seed
        // them directly to isolate and prove the activate()-time DB constraint (uq_single_active_policy)
        // under real concurrency, independent of that guard.
        String key = "concurrency-policy-" + UUID.randomUUID();
        insertApprovedVersion(key, "1.0.0", (short) 20, (short) 60);
        insertApprovedVersion(key, "2.0.0", (short) 25, (short) 65);

        UUID challengeForV1 = verifiedStepUp(lifecycleService.requestActivationStepUp(key, "1.0.0", ACTOR));
        UUID challengeForV2 = verifiedStepUp(lifecycleService.requestActivationStepUp(key, "2.0.0", ACTOR));

        List<Callable<Boolean>> contenders = List.of(
                () -> tryActivate(key, "1.0.0", challengeForV1),
                () -> tryActivate(key, "2.0.0", challengeForV2));

        List<Boolean> results = race(contenders);

        long winnerCount = results.stream().filter(Boolean::booleanValue).count();
        assertThat(winnerCount).isEqualTo(1);

        Integer activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM policy.policy_version WHERE policy_key = ? AND status = 'ACTIVE'",
                Integer.class, key);
        assertThat(activeCount).isEqualTo(1);
    }

    private boolean tryActivate(String key, String version, UUID stepUpChallengeId) {
        try {
            lifecycleService.activate(key, version, stepUpChallengeId, ACTOR);
            return true;
        } catch (DataIntegrityViolationException | IllegalPolicyTransitionException exception) {
            // lost the race for the single-active-version-per-policy-key constraint (uq_single_active_policy)
            // or for the state-machine transition (both candidates try to retire whichever wins first)
            return false;
        }
    }

    private void insertApprovedVersion(String key, String version, short allow, short stepUp) {
        String definition = "{\"allowMaxScore\":" + allow + ",\"stepUpMaxScore\":" + stepUp
                + ",\"recoveryMaxScore\":89}";
        jdbcTemplate.update(
                "INSERT INTO policy.policy_version "
                        + "(id, policy_key, version, status, definition, "
                        + "allow_max_score, step_up_max_score, recovery_max_score, created_at, "
                        + "created_by, validated_by, validated_at, approved_by, approved_at, approval_reason) "
                        + "VALUES (?, ?, ?, 'APPROVED', ?::jsonb, ?, ?, ?, now(), "
                        + "?, ?, now(), ?, now(), ?)",
                UUID.randomUUID(), key, version, definition,
                allow, stepUp, (short) 89,
                "policy-author-concurrency-test", "policy-validator-concurrency-test",
                "policy-approver-concurrency-test", "concurrency test approval");
    }

    private UUID verifiedStepUp(UUID challengeId) {
        String issuedCode = events.stream(ChallengeIssued.class)
                .filter(event -> event.challengeId().equals(challengeId))
                .reduce((first, second) -> second)
                .orElseThrow()
                .issuedCode();
        challengeService.verify(new ChallengeVerificationCommand(
                challengeId, issuedCode, ChallengePurpose.PRIVILEGED_OPERATION,
                lookUpContextId(challengeId)));
        return challengeId;
    }

    private UUID lookUpContextId(UUID challengeId) {
        return jdbcTemplate.queryForObject(
                "SELECT context_id FROM challenge.challenge_plan WHERE id = ?",
                UUID.class,
                challengeId);
    }

    private <T> List<T> race(List<Callable<T>> actions) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONTENDER_COUNT);
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
