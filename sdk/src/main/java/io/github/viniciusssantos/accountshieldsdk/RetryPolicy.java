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

    /**
     * Exponential backoff with a fixed ceiling; {@code attemptNumber} is 1-based. The exponent is
     * capped at 32 (issue #147 / F-16): {@code 1L << n} on a {@code long} only masks the shift
     * amount to its low 6 bits, so an uncapped exponent at {@code attemptNumber >= 65} silently
     * wraps back to a small shift instead of growing, and intermediate values before the wrap
     * (e.g. {@code attemptNumber} in the high 50s/60s with a realistic {@code baseDelay}) can
     * overflow {@code long} into a negative multiplier, producing a negative {@code Duration}.
     * {@code 2^32 * baseDelay} already dwarfs any realistic {@code maxDelay}, so capping here
     * changes no observable behavior for a policy actually used to completion.
     */
    public Duration delayBeforeAttempt(int attemptNumber) {
        int exponent = Math.min(Math.max(0, attemptNumber - 1), 32);
        long millis = baseDelay.toMillis() * (1L << exponent);
        return Duration.ofMillis(Math.min(millis, maxDelay.toMillis()));
    }
}
