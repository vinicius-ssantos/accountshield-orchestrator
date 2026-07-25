package io.github.viniciusssantos.accountshield.protection.internal;

import io.github.viniciusssantos.accountshield.protection.ClientId;
import io.github.viniciusssantos.accountshield.protection.ProtectionRateLimiter;
import io.github.viniciusssantos.accountshield.protection.RateLimitExceededException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InMemorySlidingWindowRateLimiter implements ProtectionRateLimiter {

    private final ConcurrentHashMap<WindowKey, ConcurrentLinkedDeque<Instant>> windows = new ConcurrentHashMap<>();
    private final int maxRequests;
    private final Duration window;

    public InMemorySlidingWindowRateLimiter(
            @Value("${accountshield.protection.rate-limit.max-requests:10}") int maxRequests,
            @Value("${accountshield.protection.rate-limit.window:60s}") Duration window) {
        if (maxRequests < 1) {
            throw new IllegalArgumentException("maxRequests must be at least 1");
        }
        Objects.requireNonNull(window, "window must not be null");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.maxRequests = maxRequests;
        this.window = window;
    }

    @Override
    public void checkLimit(ClientId clientId, String accountReference, Instant now) {
        WindowKey key = new WindowKey(clientId, accountReference);
        ConcurrentLinkedDeque<Instant> deque = windows.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        Instant cutoff = now.minus(window);

        synchronized (deque) {
            deque.removeIf(t -> t.isBefore(cutoff));
            if (deque.size() >= maxRequests) {
                Instant oldest = deque.peekFirst();
                throw new RateLimitExceededException(
                        clientId,
                        accountReference,
                        oldest != null ? oldest.plus(window) : now.plus(window));
            }
            deque.addLast(now);
        }
    }

    private record WindowKey(ClientId clientId, String accountReference) {
    }
}
