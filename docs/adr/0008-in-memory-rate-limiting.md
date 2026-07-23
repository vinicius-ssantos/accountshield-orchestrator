# ADR 0008: In-memory sliding-window rate limiting for protection requests

- Status: Accepted
- Date: 2026-07-22

## Context

Protection-decision endpoints are externally visible and could be abused by brute-force attempts that vary risk signals for the same account. Without rate limiting, an attacker can issue unlimited requests to probe policy thresholds, enumerate outcomes, or exhaust system resources.

A distributed ephemeral store (e.g. Redis) could provide shared rate-limit state across instances, but introducing that infrastructure is not yet justified for a portfolio project operating as a single-instance modular monolith.

## Decision

Implement rate limiting as an in-process, sliding-window counter keyed by account reference. The rate limiter is owned by the `protection` module and checked at the start of every protection decision, before idempotency resolution.

### Algorithm

A sliding window tracks the timestamps of recent requests per account. When a new request arrives:

1. Timestamps older than the configured window are evicted.
2. If the remaining count is at or above the maximum, the request is rejected with `429 Too Many Requests` and a `retryAfter` hint pointing to the oldest entry's expiry.
3. Otherwise, the current timestamp is recorded.

### Configuration

Defaults are configurable via `application.yml`:

```yaml
accountshield:
  protection:
    rate-limit:
      max-requests: 10
      window: 60s
```

### Placement

The rate limit check runs before fingerprint computation and idempotency resolution. This prevents attackers from bypassing the limit by reusing idempotency keys with different payloads.

### Storage

State is held in a `ConcurrentHashMap` with `ConcurrentLinkedDeque` per account. No persistence layer is involved. State is lost on restart, which is acceptable: a restart is an operational event, and the window resets within `window` seconds.

## Consequences

### Positive

- brute-force and enumeration attempts against protection decisions are bounded;
- the rate limiter is self-contained within the `protection` module with no external dependencies;
- configuration is externalized and does not require code changes;
- the `ProtectionRateLimiter` interface allows swapping the implementation for Redis-backed or distributed rate limiting without changing application logic.

### Negative

- rate limit state does not survive restarts;
- in a multi-instance deployment, each instance maintains its own counter, so the effective limit is multiplied by the instance count;
- the in-memory map grows with unique accounts until entries are lazily evicted by the sliding window.

## Guardrails

- the rate limiter uses a `Clock`-independent `Instant` parameter, keeping it testable with fixed timestamps;
- rejected requests return a `429 Too Many Requests` Problem Detail with `RATE_LIMIT_EXCEEDED` code;
- the error response does not reveal the configured limit or the account reference.

## Revisit criteria

This decision may be revisited when:

- the application is deployed across multiple instances and needs a shared rate-limit store;
- Redis is introduced for ephemeral controls;
- per-account limits need to be dynamic based on risk classification or policy.
