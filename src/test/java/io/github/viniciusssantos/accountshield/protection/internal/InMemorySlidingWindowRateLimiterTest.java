package io.github.viniciusssantos.accountshield.protection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.protection.RateLimitExceededException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemorySlidingWindowRateLimiterTest {

    private static final Instant BASE = Instant.parse("2026-07-22T12:00:00Z");

    @Test
    void allowsRequestsUpToMaxWithinWindow() {
        var limiter = new InMemorySlidingWindowRateLimiter(3, Duration.ofSeconds(60));

        assertThatCode(() -> limiter.checkLimit("acct-1", BASE)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkLimit("acct-1", BASE.plusSeconds(10))).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkLimit("acct-1", BASE.plusSeconds(20))).doesNotThrowAnyException();
    }

    @Test
    void rejectsRequestExceedingMaxWithinWindow() {
        var limiter = new InMemorySlidingWindowRateLimiter(2, Duration.ofSeconds(60));

        limiter.checkLimit("acct-2", BASE);
        limiter.checkLimit("acct-2", BASE.plusSeconds(10));

        assertThatThrownBy(() -> limiter.checkLimit("acct-2", BASE.plusSeconds(20)))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void allowsRequestsAgainAfterWindowExpires() {
        var limiter = new InMemorySlidingWindowRateLimiter(2, Duration.ofSeconds(60));

        limiter.checkLimit("acct-3", BASE);
        limiter.checkLimit("acct-3", BASE.plusSeconds(30));

        assertThatCode(() -> limiter.checkLimit("acct-3", BASE.plusSeconds(61)))
                .doesNotThrowAnyException();
    }

    @Test
    void tracksAccountsIndependently() {
        var limiter = new InMemorySlidingWindowRateLimiter(1, Duration.ofSeconds(60));

        limiter.checkLimit("acct-a", BASE);
        assertThatCode(() -> limiter.checkLimit("acct-b", BASE)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.checkLimit("acct-a", BASE.plusSeconds(10)))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void retryAfterPointsToWindowExpiryFromOldestEntry() {
        var limiter = new InMemorySlidingWindowRateLimiter(2, Duration.ofSeconds(60));

        limiter.checkLimit("acct-4", BASE);
        limiter.checkLimit("acct-4", BASE.plusSeconds(15));

        assertThatThrownBy(() -> limiter.checkLimit("acct-4", BASE.plusSeconds(20)))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(ex -> {
                    var rateLimitEx = (RateLimitExceededException) ex;
                    assertThat(rateLimitEx.retryAfter()).isEqualTo(BASE.plusSeconds(60));
                });
    }

    @Test
    void rejectsInvalidConfig() {
        assertThatThrownBy(() -> new InMemorySlidingWindowRateLimiter(0, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemorySlidingWindowRateLimiter(5, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
