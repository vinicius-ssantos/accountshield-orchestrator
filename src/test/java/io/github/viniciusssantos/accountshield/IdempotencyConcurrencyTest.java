package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignalEnvelope;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import io.github.viniciusssantos.accountshield.risk.SignalConfidence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class IdempotencyConcurrencyTest {

    @Autowired
    private ProtectionDecisionService protectionDecisionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void eightConcurrentEquivalentRequestsAllReceiveTheSameResult() throws Exception {
        String idempotencyKey = "idem-concurrent-" + UUID.randomUUID();
        String accountRef = "account-concurrent-" + UUID.randomUUID();
        RiskSignalEnvelope signals = new RiskSignalEnvelope(
                new RiskSignals(2, false, false, false, NetworkRiskLevel.LOW),
                "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true);

        int threadCount = 8;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        List<ProtectionDecisionResult> successes = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        ProtectionDecisionResult result = protectionDecisionService.decide(
                                new ProtectionDecisionCommand(
                                        accountRef,
                                        ProtectionEventType.LOGIN_ATTEMPT,
                                        signals,
                                        idempotencyKey));
                        successes.add(result);
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                });
            }

            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            executor.shutdown();
            boolean terminated = executor.awaitTermination(30, TimeUnit.SECONDS);
            assertThat(terminated).as("all threads should terminate").isTrue();
        } finally {
            if (!executor.isTerminated()) {
                executor.shutdownNow();
            }
        }

        assertThat(failures)
                .as("no racer should see a raw database error or a spurious conflict")
                .isEmpty();
        assertThat(successes)
                .as("eight or more equivalent concurrent requests all receive the same result")
                .hasSize(threadCount);
        assertThat(successes.stream().map(ProtectionDecisionResult::decisionId).distinct().count())
                .as("every racer receives the same decisionId, not just an equal-looking copy")
                .isEqualTo(1);
        assertThat(successes.stream().map(ProtectionDecisionResult::protectionRequestId).distinct().count())
                .isEqualTo(1);

        UUID decisionId = successes.getFirst().decisionId();
        UUID protectionRequestId = successes.getFirst().protectionRequestId();

        long idempotencyRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM protection.idempotency_record WHERE idempotency_key = ?",
                Long.class, idempotencyKey);
        assertThat(idempotencyRows).as("only one idempotency record should exist").isEqualTo(1);

        long requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM protection.protection_request WHERE id = ?",
                Long.class, protectionRequestId);
        assertThat(requestRows).as("only one protection request should exist").isEqualTo(1);

        long traceRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit.decision_trace WHERE id = ?",
                Long.class, decisionId);
        assertThat(traceRows).as("only one decision trace should exist").isEqualTo(1);

        long outboxRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox.outbox_event WHERE aggregate_type = 'ProtectionDecision' "
                        + "AND aggregate_id = ?",
                Long.class, decisionId.toString());
        assertThat(outboxRows).as("only one outbox record should exist").isEqualTo(1);

        jdbcTemplate.update("DELETE FROM protection.idempotency_record WHERE idempotency_key = ?", idempotencyKey);
        jdbcTemplate.update("DELETE FROM protection.protection_request WHERE account_reference = ?", accountRef);
    }
}
