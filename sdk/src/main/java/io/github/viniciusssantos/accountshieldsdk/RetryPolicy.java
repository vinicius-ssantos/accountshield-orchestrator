package io.github.viniciusssantos.accountshieldsdk;

import java.time.Duration;
import java.util.Set;

/**
 * Bounded-retry-with-backoff policy that only ever retries operations the caller has explicitly
 * marked safe -- GET requests (safe by HTTP semantics) or a POST the caller has told
 * {@link AccountShieldClient} carries an idempotency key (see
 * {@code ProtectionDecisionRequest.Builder#idempotencyKey}). {@link AccountShieldClient} never
 * infers safety on its own: a POST without an explicit idempotency key is never retried, and a
 * challenge-verification call is never retried at all (each attempt consumes the challenge's
 * attempt budget -- retrying it would be actively harmful, not just wasteful). This is the concrete
 * mechanism behind issue #55's "retries occur only for safe operations" acceptance criterion.
 */
public final class RetryPolicy {

    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(429, 502, 503, 504);

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;

    public RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (maxAttempts > 62) {
            throw new IllegalArgumentException("maxAttempts must not exceed 62");
        }
        this.maxAttempts = maxAttempts;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
    }

    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy(3, Duration.ofMillis(200), Duration.ofSeconds(2));
    }

    public static RetryPolicy noRetries() {
        return new RetryPolicy(1, Duration.ZERO, Duration.ZERO);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** {@code attemptNumber} is 1-based (the attempt that just failed). */
    public boolean shouldRetryAfterFailure(int attemptNumber, boolean operationIsSafeToRetry, boolean networkFailure, int httpStatus) {
        if (!operationIsSafeToRetry || attemptNumber >= maxAttempts) {
            return false;
        }
        return networkFailure || RETRYABLE_STATUS_CODES.contains(httpStatus);
    }

    /** Exponential backoff with a fixed ceiling; {@code attemptNumber} is 1-based. */
    public Duration delayBeforeAttempt(int attemptNumber) {
        long millis = baseDelay.toMillis() * (1L << Math.max(0, attemptNumber - 1));
        return Duration.ofMillis(Math.min(millis, maxDelay.toMillis()));
    }
}
