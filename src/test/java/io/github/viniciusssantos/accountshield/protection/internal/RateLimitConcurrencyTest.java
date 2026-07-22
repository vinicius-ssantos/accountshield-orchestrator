package io.github.viniciusssantos.accountshield.protection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.protection.ProtectionRateLimiter;
import io.github.viniciusssantos.accountshield.protection.RateLimitExceededException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RateLimitConcurrencyTest {

    private static final int THREADS = 50;
    private static final int MAX_REQUESTS = 5;
    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");

    @Test
    void allowsExactlyMaxRequestsUnderConcurrentLoad() throws InterruptedException {
        ProtectionRateLimiter limiter = new InMemorySlidingWindowRateLimiter(MAX_REQUESTS, Duration.ofSeconds(60));

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREADS);
        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        limiter.checkLimit("concurrent-acct", NOW);
                        allowed.incrementAndGet();
                    } catch (RateLimitExceededException e) {
                        rejected.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startGate.countDown();
            doneLatch.await();
        }

        assertThat(allowed.get()).isEqualTo(MAX_REQUESTS);
        assertThat(rejected.get()).isEqualTo(THREADS - MAX_REQUESTS);
    }

    @Test
    void concurrentRequestsForDifferentAccountsAllSucceed() throws InterruptedException {
        ProtectionRateLimiter limiter = new InMemorySlidingWindowRateLimiter(1, Duration.ofSeconds(60));

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREADS);
        AtomicInteger allowed = new AtomicInteger(0);

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                final String account = "acct-" + i;
                pool.submit(() -> {
                    try {
                        startGate.await();
                        limiter.checkLimit(account, NOW);
                        allowed.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startGate.countDown();
            doneLatch.await();
        }

        assertThat(allowed.get()).isEqualTo(THREADS);
    }
}
