package io.github.viniciusssantos.accountshield.protection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.protection.ClientId;
import io.github.viniciusssantos.accountshield.protection.RateLimitExceededException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemorySlidingWindowRateLimiterTest {

    private static final Instant BASE = Instant.parse("2026-07-22T12:00:00Z");
    private static final ClientId CLIENT = ClientId.DEFAULT;

    @Test
    void allowsRequestsUpToMaxWithinWindow() {
        var limiter = new InMemorySlidingWindowRateLimiter(3, Duration.ofSeconds(60));

        assertThatCode(() -> limiter.checkLimit(CLIENT, "acct-1", BASE)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkLimit(CLIENT, "acct-1", BASE.plusSeconds(10))).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkLimit(CLIENT, "acct-1", BASE.plusSeconds(20))).doesNotThrowAnyException();
    }

    @Test
    void rejectsRequestExceedingMaxWithinWindow() {
        var limiter = new InMemorySlidingWindowRateLimiter(2, Duration.ofSeconds(60));

        limiter.checkLimit(CLIENT, "acct-2", BASE);
        limiter.checkLimit(CLIENT, "acct-2", BASE.plusSeconds(10));

        assertThatThrownBy(() -> limiter.checkLimit(CLIENT, "acct-2", BASE.plusSeconds(20)))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void allowsRequestsAgainAfterWindowExpires() {
        var limiter = new InMemorySlidingWindowRateLimiter(2, Duration.ofSeconds(60));

        limiter.checkLimit(CLIENT, "acct-3", BASE);
        limiter.checkLimit(CLIENT, "acct-3", BASE.plusSeconds(30));

        assertThatCode(() -> limiter.checkLimit(CLIENT, "acct-3", BASE.plusSeconds(61)))
                .doesNotThrowAnyException();
    }

    @Test
    void tracksAccountsIndependently() {
        var limiter = new InMemorySlidingWindowRateLimiter(1, Duration.ofSeconds(60));

        limiter.checkLimit(CLIENT, "acct-a", BASE);
        assertThatCode(() -> limiter.checkLimit(CLIENT, "acct-b", BASE)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.checkLimit(CLIENT, "acct-a", BASE.plusSeconds(10)))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void tracksClientsIndependentlyForTheSameAccountReference() {
        var limiter = new InMemorySlidingWindowRateLimiter(1, Duration.ofSeconds(60));
        ClientId clientA = new ClientId("client-a");
        ClientId clientB = new ClientId("client-b");

        limiter.checkLimit(clientA, "shared-acct", BASE);
        assertThatCode(() -> limiter.checkLimit(clientB, "shared-acct", BASE)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.checkLimit(clientA, "shared-acct", BASE.plusSeconds(10)))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void retryAfterPointsToWindowExpiryFromOldestEntry() {
        var limiter = new InMemorySlidingWindowRateLimiter(2, Duration.ofSeconds(60));

        limiter.checkLimit(CLIENT, "acct-4", BASE);
        limiter.checkLimit(CLIENT, "acct-4", BASE.plusSeconds(15));

        assertThatThrownBy(() -> limiter.checkLimit(CLIENT, "acct-4", BASE.plusSeconds(20)))
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

    /**
     * Issue #149 / F-21: {@code checkLimit} alone never leaves a key both present and empty, so
     * an abandoned account reference's map entry survives forever without a periodic sweep.
     * Proves the sweep actually shrinks the tracked-window count once every timestamp in a key's
     * window has expired, without disturbing a key that is still active.
     */
    @Test
    void evictExpiredWindowsRemovesOnlyEntriesWithNoLiveTimestampsLeft() {
        var limiter = new InMemorySlidingWindowRateLimiter(5, Duration.ofSeconds(60));

        limiter.checkLimit(CLIENT, "abandoned-acct", BASE);
        limiter.checkLimit(CLIENT, "still-active-acct", BASE);
        assertThat(limiter.trackedWindowCount()).isEqualTo(2);

        Instant afterAbandonedWindowExpires = BASE.plusSeconds(61);
        limiter.checkLimit(CLIENT, "still-active-acct", afterAbandonedWindowExpires);

        limiter.evictExpiredWindows(afterAbandonedWindowExpires);

        assertThat(limiter.trackedWindowCount()).isEqualTo(1);
        assertThatCode(() -> limiter.checkLimit(CLIENT, "abandoned-acct", afterAbandonedWindowExpires))
                .doesNotThrowAnyException();
    }

    @Test
    void evictExpiredWindowsIsANoOpWhenNothingHasExpired() {
        var limiter = new InMemorySlidingWindowRateLimiter(1, Duration.ofSeconds(60));

        limiter.checkLimit(CLIENT, "acct-5", BASE);
        limiter.evictExpiredWindows(BASE.plusSeconds(10));

        assertThat(limiter.trackedWindowCount()).isEqualTo(1);
        assertThatThrownBy(() -> limiter.checkLimit(CLIENT, "acct-5", BASE.plusSeconds(11)))
                .isInstanceOf(RateLimitExceededException.class);
    }
}
