package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.challenge.ChallengeIssued;
import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeVerificationCommand;
import io.github.viniciusssantos.accountshield.policy.CreatePolicyCommand;
import io.github.viniciusssantos.accountshield.policy.IllegalPolicyTransitionException;
import io.github.viniciusssantos.accountshield.policy.PolicyLifecycleService;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionSummary;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionRepository;
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
    private static final String AUTHOR = "policy-author-concurrency-test";
    private static final String APPROVER = "policy-approver-concurrency-test";
    private static final String ACTOR = "policy-admin-concurrency-test";

    @Autowired
    private PolicyLifecycleService lifecycleService;

    @Autowired
    private ChallengeService challengeService;

    @Autowired
    private PolicyVersionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEvents events;

    @Test
    void concurrentActivationOfTwoApprovedVersionsHasExactlyOneWinner() throws Exception {
        String key = "concurrency-policy-" + UUID.randomUUID();

        approveDraft(key, "1.0.0", (short) 20, (short) 60);
        approveDraft(key, "2.0.0", (short) 25, (short) 65);

        UUID challengeForV1 = verifiedStepUp(lifecycleService.requestActivationStepUp(key, "1.0.0", ACTOR));
        UUID challengeForV2 = verifiedStepUp(lifecycleService.requestActivationStepUp(key, "2.0.0", ACTOR));

        List<Callable<Boolean>> contenders = List.of(
                () -> tryActivate(key, "1.0.0", challengeForV1),
                () -> tryActivate(key, "2.0.0", challengeForV2));

        List<Boolean> results = race(contenders);

        long winnerCount = results.stream().filter(Boolean::booleanValue).count();
        assertThat(winnerCount).isEqualTo(1);

        repository.flush();
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

    private void approveDraft(String key, String version, short allow, short stepUp) {
        lifecycleService.createDraft(new CreatePolicyCommand(key, version, allow, stepUp), AUTHOR);
        lifecycleService.validate(key, version, ACTOR);
        UUID challengeId = verifiedStepUp(lifecycleService.requestApprovalStepUp(key, version, APPROVER));
        PolicyVersionSummary approved = lifecycleService.approve(
                key, version, challengeId, APPROVER, "concurrency test approval");
        assertThat(approved.governance().approvedBy()).isEqualTo(APPROVER);
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
