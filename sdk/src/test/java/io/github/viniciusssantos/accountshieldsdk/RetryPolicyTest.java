package io.github.viniciusssantos.accountshieldsdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void neverRetriesAnUnsafeOperationRegardlessOfFailureType() {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(10), Duration.ofSeconds(1));

        assertThat(policy.shouldRetryAfterFailure(1, false, true, -1)).isFalse();
        assertThat(policy.shouldRetryAfterFailure(1, false, false, 503)).isFalse();
    }

    @Test
    void retriesASafeOperationOnNetworkFailureAndRetryableStatusCodes() {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(10), Duration.ofSeconds(1));

        assertThat(policy.shouldRetryAfterFailure(1, true, true, -1)).isTrue();
        assertThat(policy.shouldRetryAfterFailure(1, true, false, 503)).isTrue();
        assertThat(policy.shouldRetryAfterFailure(1, true, false, 429)).isTrue();
    }

    @Test
    void neverRetriesAClientErrorEvenWhenSafe() {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(10), Duration.ofSeconds(1));

        assertThat(policy.shouldRetryAfterFailure(1, true, false, 400)).isFalse();
        assertThat(policy.shouldRetryAfterFailure(1, true, false, 404)).isFalse();
        assertThat(policy.shouldRetryAfterFailure(1, true, false, 409)).isFalse();
    }

    @Test
    void stopsRetryingOnceMaxAttemptsIsReached() {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(10), Duration.ofSeconds(1));

        assertThat(policy.shouldRetryAfterFailure(3, true, true, -1)).isFalse();
    }

    @Test
    void backoffGrowsExponentiallyUpToTheConfiguredCeiling() {
        RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(100), Duration.ofMillis(350));

        assertThat(policy.delayBeforeAttempt(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.delayBeforeAttempt(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.delayBeforeAttempt(3)).isEqualTo(Duration.ofMillis(350));
    }

    @Test
    void rejectsMaxAttemptsAbove62() {
        assertThatThrownBy(() -> new RetryPolicy(63, Duration.ofMillis(10), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed 62");
    }

    @Test
    void doesNotOverflowWithAnExtremelyLargeAttemptNumber() {
        RetryPolicy policy = new RetryPolicy(62, Duration.ofMillis(1), Duration.ofSeconds(10));

        assertThat(policy.delayBeforeAttempt(10_000).toMillis()).isPositive();
    }
}
