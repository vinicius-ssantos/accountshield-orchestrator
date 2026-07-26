package io.github.viniciusssantos.accountshield.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.PostgreSqlTestConfiguration;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventEntity;
import io.github.viniciusssantos.accountshield.outbox.internal.persistence.OutboxEventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class OutboxClaimStoreConcurrencyTest {

    private static final int EVENT_COUNT = 10;
    private static final int CONTENDER_COUNT = 4;

    @Autowired
    private OutboxClaimStore claimStore;

    @Autowired
    private OutboxEventRepository repository;

    @Test
    void concurrentClaimsNeverDoubleClaimTheSameEvent() throws Exception {
        Instant now = Instant.now();
        List<UUID> seeded = new ArrayList<>();
        for (int i = 0; i < EVENT_COUNT; i++) {
            UUID id = UUID.randomUUID();
            seeded.add(id);
            // occurred_at is set far in the past so these rows always sort first (claim query
            // orders by occurred_at ASC), ahead of any PENDING rows other tests may have left
            // behind in this shared Postgres instance
            repository.save(new OutboxEventEntity(
                    id, "Test", "agg-" + id, "TEST_EVENT", "{}", now.minusSeconds(999_999)));
        }

        List<Callable<List<UUID>>> contenders = new ArrayList<>();
        for (int i = 0; i < CONTENDER_COUNT; i++) {
            String instanceId = "instance-" + i;
            contenders.add(() -> claimStore.claimBatch(
                            Instant.now(), Instant.now().minusSeconds(120), instanceId, EVENT_COUNT)
                    .stream().map(ClaimedOutboxEvent::id).toList());
        }

        List<List<UUID>> results = race(contenders);

        // other tests sharing this Postgres instance may also leave PENDING rows behind;
        // this test only asserts safety over the batch it seeded itself
        List<UUID> seededIdsClaimed = results.stream()
                .flatMap(List::stream)
                .filter(seeded::contains)
                .toList();
        assertThat(seededIdsClaimed).hasSize(EVENT_COUNT);
        assertThat(seededIdsClaimed).doesNotHaveDuplicates();
        assertThat(new HashSet<>(seededIdsClaimed)).isEqualTo(new HashSet<>(seeded));
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
