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
        Instant cutoff = now.minus(window);
        // Prune, check, and append inside a single compute() call so the whole mutation is
        // atomic with respect to the map (issue #149 / F-21): computeIfAbsent followed by a
        // separately-acquired "synchronized (deque)" left a window where a concurrent eviction
        // could remove this exact key -> deque mapping between the two steps, silently losing
        // the timestamp this call was about to append.
        windows.compute(key, (k, existing) -> {
            ConcurrentLinkedDeque<Instant> deque = existing != null ? existing : new ConcurrentLinkedDeque<>();
            deque.removeIf(t -> t.isBefore(cutoff));
            if (deque.size() >= maxRequests) {
                Instant oldest = deque.peekFirst();
                throw new RateLimitExceededException(
                        clientId,
                        accountReference,
                        oldest != null ? oldest.plus(window) : now.plus(window));
            }
            deque.addLast(now);
            return deque;
        });
    }

    /**
     * Removes entries whose window has held nothing but expired timestamps since {@code now}
     * (issue #149 / F-21). {@link #checkLimit} alone never leaves a key both present and empty --
     * a completed call always ends with at least one timestamp, and the throwing path requires at
     * least {@code maxRequests} of them -- so a key that simply stops being used (an abandoned or
     * one-off account reference) is never cleaned up by access alone; without a periodic sweep the
     * map grows without bound for the life of the process. Uses the same per-key {@link
     * ConcurrentHashMap#compute} atomicity {@link #checkLimit} uses, so a concurrent {@code
     * checkLimit} call for the same key can never lose a just-appended timestamp to a racing
     * removal here.
     */
    void evictExpiredWindows(Instant now) {
        Instant cutoff = now.minus(window);
        for (WindowKey key : windows.keySet()) {
            windows.compute(key, (k, deque) -> {
                if (deque == null) {
                    return null;
                }
                deque.removeIf(t -> t.isBefore(cutoff));
                return deque.isEmpty() ? null : deque;
            });
        }
    }

    int trackedWindowCount() {
        return windows.size();
    }

    private record WindowKey(ClientId clientId, String accountReference) {
    }
}
