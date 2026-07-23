# ADR 0009: Outbox relay with simulated publisher and bounded retry

- Status: Accepted
- Date: 2026-07-23

## Context

The transactional outbox pattern was introduced in Phase 8 to reliably capture domain events (`ProtectionDecisionMade`, `ChallengeCompleted`, `PolicyActivated`, `RecoveryCompleted`) within the same database transaction as the business operation. Events are written to the `outbox.outbox_event` table with fields for tracking publication (`published_at`, `attempt_count`, `last_error`, `version`) and a partial index on unpublished events.

However, the outbox was only half-implemented: events were recorded but never dispatched. No component read unpublished events or attempted to relay them to an external consumer. The pattern's reliability guarantee — at-least-once delivery after the originating transaction commits — was unfulfilled.

## Decision

Introduce an `OutboxRelay` component that polls unpublished events on a fixed schedule and dispatches them through a pluggable `OutboxEventPublisher` port.

### Relay design

The `OutboxRelay` runs on a configurable fixed delay (default 5 seconds) and processes a bounded batch of unpublished events ordered by `occurred_at`:

1. Fetch unpublished events (`published_at IS NULL`) ordered by `occurred_at`, limited to `batch-size` (default 50).
2. Skip events whose `attempt_count` has reached `max-attempts` (default 5) — these are effectively dead-lettered and require manual intervention.
3. Attempt to publish the event via `OutboxEventPublisher`.
4. On success: mark `published_at`.
5. On failure: increment `attempt_count` and record a bounded `last_error` (max 1000 characters).
6. Persist the result with optimistic locking (`@Version`). If another instance already handled the event, the `OptimisticLockingFailureException` is swallowed.

### Publisher port

`OutboxEventPublisher` is a public interface in the `outbox` module:

```java
public interface OutboxEventPublisher {
    void publish(OutboxMessage message);
}
```

A `LoggingOutboxEventPublisher` is registered by default via `@ConditionalOnMissingBean`. It logs the published event at INFO level using the `accountshield.outbox` logger. This mirrors the simulated-provider approach used for challenges (ADR 0004): the portfolio project ships with a no-op publisher and defers real infrastructure integration to a future ADR.

### Configuration

```yaml
accountshield:
  outbox:
    relay:
      fixed-delay: 5s
      batch-size: 50
      max-attempts: 5
```

## Consequences

### Positive

- the outbox pattern's reliability guarantee is fulfilled: events are eventually dispatched after the originating transaction commits;
- the `OutboxEventPublisher` port allows swapping the simulated logger for a real message broker without changing application logic;
- optimistic locking prevents duplicate processing across concurrent relay instances;
- bounded retry with dead-lettering prevents a poison message from blocking the queue indefinitely;
- all parameters are externalized and do not require code changes.

### Negative

- in a single-instance deployment, a crash between the publisher call and the `published_at` update results in at-least-once (not exactly-once) delivery — consumers must be idempotent;
- the default publisher performs no real external delivery, so events are only logged until a real publisher is introduced;
- dead-lettered events (exceeded `max-attempts`) accumulate silently and require operational monitoring;
- the relay holds no distributed lock, so multi-instance deployments require coordination or idempotent consumers to avoid duplicate work.

## Guardrails

- the publisher is called outside any database transaction so that external I/O does not hold connection or transaction resources;
- error messages are bounded to 1000 characters to prevent unbounded storage growth;
- the relay logs warnings on publish failures and debug-level messages on optimistic-locking conflicts;
- in tests, the relay fixed delay is set to a large value to prevent interference with assertions about the just-recorded (unpublished) state.

## Revisit criteria

This decision may be revisited when:

- a real message broker or event stream is introduced (requiring its own ADR);
- the application is deployed across multiple instances and needs a distributed relay lock;
- dead-letter monitoring requires automated alerting or a manual replay endpoint;
- at-least-once delivery semantics are insufficient and exactly-once delivery is required.
