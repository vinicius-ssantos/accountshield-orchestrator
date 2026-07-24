package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.protection.ConflictingIdempotencyRequestException;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
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
    void concurrentRequestsWithSameKeyProduceSingleDecision() throws Exception {
        String idempotencyKey = "idem-concurrent-" + UUID.randomUUID();
        String accountRef = "account-concurrent-" + UUID.randomUUID();
        RiskSignals signals = new RiskSignals(2, false, false, false, NetworkRiskLevel.LOW);

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

        long rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM protection.idempotency_record WHERE idempotency_key = ?",
                Long.class, idempotencyKey);
        assertThat(rowCount).as("only one idempotency record should exist").isEqualTo(1);

        long requestCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM protection.protection_request WHERE request_fingerprint = ("
                        + "SELECT request_fingerprint FROM protection.idempotency_record WHERE idempotency_key = ?)",
                Long.class, idempotencyKey);
        assertThat(requestCount).as("only one protection request should exist").isEqualTo(1);

        assertThat(successes)
                .as("exactly one racer should receive a real decision")
                .hasSize(1);
        assertThat(failures)
                .as("every racer that lost the race should get a stable conflict, never a raw database error")
                .hasSize(threadCount - 1)
                .allSatisfy(failure -> assertThat(failure).isInstanceOf(ConflictingIdempotencyRequestException.class));

        jdbcTemplate.update("DELETE FROM protection.idempotency_record WHERE idempotency_key = ?", idempotencyKey);
        jdbcTemplate.update("DELETE FROM protection.protection_request WHERE account_reference = ?", accountRef);
    }
}
