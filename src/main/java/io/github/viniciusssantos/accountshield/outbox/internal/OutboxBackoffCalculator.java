package io.github.viniciusssantos.accountshield.outbox.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;

/**
 * Bounded exponential backoff with half-jitter: the delay before the next attempt is always
 * between 50% and 100% of {@code min(baseDelay * 2^attemptCount, maxDelay)}, which spreads out
 * retries for events that failed around the same time without ever exceeding {@code maxDelay}.
 */
class OutboxBackoffCalculator {

    private static final int MAX_SHIFT = 30;

    private final long baseDelayMillis;
    private final long maxDelayMillis;
    private final Random random;

    OutboxBackoffCalculator(Duration baseDelay, Duration maxDelay) {
        this(baseDelay, maxDelay, new Random());
    }

    OutboxBackoffCalculator(Duration baseDelay, Duration maxDelay, Random random) {
        this.baseDelayMillis = baseDelay.toMillis();
        this.maxDelayMillis = maxDelay.toMillis();
        this.random = random;
    }

    Instant nextAttemptAt(Instant now, int attemptCount) {
        int cappedAttempt = Math.min(attemptCount, MAX_SHIFT);
        long uncappedMillis = baseDelayMillis * (1L << cappedAttempt);
        long cappedMillis = Math.min(uncappedMillis, maxDelayMillis);
        long half = cappedMillis / 2;
        long jitteredMillis = half + random.nextLong(half + 1);
        return now.plusMillis(jitteredMillis);
    }
}
