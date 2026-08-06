# ADR 0023: Outbox delivery gains explicit claiming, backoff, and dead letters

- Status: Accepted
- Date: 2026-07-26
- Supersedes: [ADR 0009](0009-outbox-relay-with-simulated-publisher.md)

## Context

P1 issue #23. `docs/roadmap.md` Gate 5 sequences `#22→#23→#47→#52`, exit criteria "multi-instance relay does not publish concurrently under normal operation" and "poison events become visible dead letters" — near-verbatim to this issue. `docs/features/README.md` marked two rows **Planned** pointing at #23: "Multi-instance claiming and backoff" and "Versioned minimized integration events." ADR 0009's own Revisit criteria already named exactly these gaps as future work.

Direct code inspection confirmed both bugs the issue names precisely:

- `OutboxRelay.dispatchSingle()`: once `attemptCount >= maxAttempts`, the method **just returns** — no status is set, nothing marks the row as done. It stays `published_at IS NULL` forever and keeps being reselected by `findUnpublished` on every subsequent poll: a silent, permanent skip, not a visible dead letter.
- `publisher.publish(...)` was called with **no lock of any kind** beforehand. `@Version` optimistic locking only rejects the second **write** — it does nothing to stop two relay instances from both reading the same unpublished row and both calling the (simulated) publisher before either saves. ADR 0009 itself admitted this tension ("optimistic locking prevents duplicate processing" as a claimed positive, "the relay holds no distributed lock... require coordination" as an admitted negative) without resolving it.

## Decision

### Explicit state machine replaces `published_at IS NULL` inference

`outbox.outbox_event` gains `status` (`PENDING`/`IN_PROGRESS`/`PUBLISHED`/`DEAD_LETTERED`), `next_attempt_at`, `claimed_at`, `claimed_by`, `dead_lettered_at` (migration `V19`, backfilling existing rows from their current `published_at`/`attempt_count`). Publication state is no longer inferred — it is recorded.

### Atomic claiming via `FOR UPDATE SKIP LOCKED`, not JPA optimistic locking

New `outbox.internal.OutboxClaimStore`, `JdbcTemplate`-based (matching this codebase's established raw-SQL style for concurrency-sensitive or read-heavy paths, e.g. `JdbcDecisionTraceQuery`), executes one atomic statement per claim:

```sql
WITH claimed AS (
    UPDATE outbox.outbox_event
       SET status = 'IN_PROGRESS', claimed_at = ?, claimed_by = ?
     WHERE id IN (
         SELECT id FROM outbox.outbox_event
          WHERE (status = 'PENDING' AND next_attempt_at <= ?)
             OR (status = 'IN_PROGRESS' AND claimed_at < ?)
          ORDER BY occurred_at ASC
          LIMIT ?
            FOR UPDATE SKIP LOCKED
     )
    RETURNING id, aggregate_type, aggregate_id, event_type, payload::text, occurred_at, attempt_count
)
SELECT * FROM claimed ORDER BY occurred_at ASC
```

`FOR UPDATE SKIP LOCKED` is what makes "multiple relay instances do not publish the same event concurrently" true: a second instance's claim query skips any row a first instance's still-open transaction already holds, at the database level. This **is** the concurrency-safety mechanism ADR 0009's "needs a distributed relay lock" revisit note called for — no separate application-level lock is needed on top of it. The claiming half of `outbox_event`'s lifecycle moves off JPA `save()` entirely for this reason: optimistic locking was exactly the mechanism diagnosed as insufficient, so re-using it for the fix would reproduce the same class of bug. The stale-`IN_PROGRESS` branch (claimed longer ago than `accountshield.outbox.relay.claim-timeout`, default 2 minutes) reclaims work from a crashed instance — the accepted consequence is that a crash between a successful `publish()` call and the status update being written results in the event being reprocessed and potentially published twice. This is the existing, explicitly-accepted at-least-once guarantee (unchanged from ADR 0009), not a new risk.

### Bounded exponential backoff with jitter

`OutboxBackoffCalculator`: `delay = min(baseDelay * 2^attemptCount, maxDelay)`, then half-jitter (uniformly between 50% and 100% of that value) to avoid every failed event retrying in lockstep. Configurable (`accountshield.outbox.relay.backoff.base-delay` default 1s, `.max-delay` default 5m).

### Dead-lettering replaces the silent skip

When `attemptCount` (after increment) reaches `maxAttempts`, the event moves to `DEAD_LETTERED` (with `dead_lettered_at`) instead of being silently left behind — fixing the exact bug this issue names. Dead-lettered rows are excluded from the claim query entirely (only `PENDING`/stale-`IN_PROGRESS` rows are claimable), so "dead letters do not starve newer events" holds by construction: they are not even candidates in the `ORDER BY occurred_at LIMIT batchSize` competition.

### Manual requeue, restricted to `SECURITY_OPERATOR`

`OutboxAdminService.requeue(eventId, actor)`, exposed at `POST /api/v1/outbox/{id}/requeue`, gated by the existing JWT-role scheme (`SecurityConfig`, reusing `SECURITY_OPERATOR` — already used for `/api/v1/recovery/*/review*` — no new authorization mechanism introduced). Legal only from `DEAD_LETTERED` (`OutboxEventNotDeadLetteredException` otherwise, mapped to 409); resets `status=PENDING`, `next_attempt_at=now`, `attempt_count=0`, and clears `last_error`/`claimed_at`/`claimed_by`/`dead_lettered_at` — a deliberate fresh start, not a resume, which is what "operators can requeue an event safely" asks for.

### Versioned integration-event envelope

New public `outbox.IntegrationEventEnvelope(eventId, schemaVersion, correlationId, occurredAt, data)` and `outbox.IntegrationEventSchema.CURRENT_VERSION = "integration-event-1.0"`. `OutboxEventRecorder` wraps every domain event in this envelope before serializing (`eventId` = the outbox row's own id; `correlationId` = `aggregateId`, the existing natural "what this event is about" key). This is a write-time-only change: `OutboxMessage`/`OutboxEventPublisher` (the public port) need no changes at all, since the fully-wrapped envelope JSON already flows through `OutboxMessage.payload()` unchanged. Account-reference redaction (`AccountPseudonymizer` -> `subjectToken`, from #32/#35) is unaffected and continues to satisfy "sensitive fields have an explicit classification and retention rule."

### Retention for `PUBLISHED` and `DEAD_LETTERED` rows

`OutboxEventRetentionCleanup`, following the exact shape of the three existing retention jobs (`ChallengePlanRetentionCleanup`, `RecoveryFlowRetentionCleanup`, `IdempotencyRecordRetentionCleanup`): purges `PUBLISHED` rows older than `accountshield.outbox.retention.published-ttl` (default 7d) and `DEAD_LETTERED` rows older than `.dead-lettered-ttl` (default 30d, longer — DLQ rows are kept for investigation).

### Metrics: counters for dispatch outcomes, this codebase's first gauges for point-in-time health

`accountshield.outbox.relay.dispatch` (`Counter`, tag `outcome=published|failed|dead_lettered`) follows the established `Counter.builder(...).tag(...).register(meterRegistry)` style. `accountshield.outbox.dead_lettered.count` and `accountshield.outbox.pending.oldest_age_seconds` are `Gauge`s — the first use of Micrometer's `Gauge` API in this codebase, applied via the standard `Gauge.builder(name, state, valueFunction).register(...)` pattern with no new infrastructure. These are the "metrics for lag, retries, oldest pending event, and DLQ count" the issue asks for; wiring them into a real alerting system is explicitly out of scope (see below).

## Alternatives considered

- **A separate distributed relay lock** (e.g. a dedicated lock table or advisory lock held for the whole poll cycle) — rejected; row-level `FOR UPDATE SKIP LOCKED` already solves the exact problem at a finer grain with no additional infrastructure.
- **Keeping `@Version` optimistic locking for claim-time mutation** — rejected; it is exactly the mechanism the issue diagnosed as insufficient (it stops a conflicting *write*, not a duplicate *read-then-publish*).
- **Bundling `schemaVersion`/`correlationId` as new fields on `OutboxMessage`** — rejected; the acceptance criterion is about the JSON payload's *content*, already satisfiable by wrapping at write time without touching the publisher port, keeping the blast radius smaller.
- **Real alerting integration** on top of the new metrics — rejected as out of scope; this repo's guardrails require its own accepted issue/ADR before integrating real external infrastructure.

## Consequences

### Positive

- the exact silent-skip bug (dead events reselected forever) is fixed;
- multiple relay instances can run safely against the same table with no coordination beyond Postgres itself;
- a poison event becomes a visible, queryable, individually-actionable `DEAD_LETTERED` row instead of an invisible permanent no-op;
- integration payloads are self-describing (`eventId`/`schemaVersion`/`correlationId`/`occurredAt`), enabling schema evolution without touching the publisher port.

### Negative

- at-least-once delivery remains the guarantee, not exactly-once — a crash between `publish()` succeeding and the status update committing (or before the stale-claim timeout elapses) can reprocess and re-publish the same event; consumers must remain idempotent, exactly as ADR 0009 already required;
- `claim-timeout` must be tuned longer than any realistic `publish()` call, or live claims risk being reclaimed and double-processed prematurely — an operational parameter to monitor, not a structural flaw.

## Guardrails

- `FOR UPDATE SKIP LOCKED` claim query is the sole mechanism serializing concurrent claims — no other locking layer exists or is needed;
- `publisher.publish(...)` is still called outside any surrounding transaction (unchanged guardrail from ADR 0009) — external I/O never holds a database connection open;
- dead-lettered rows are never selected by the claim query — verified by `OutboxClaimStoreConcurrencyTest` and the relay's dispatch tests;
- `requeue` is only legal from `DEAD_LETTERED` — verified by `OutboxAdminApplicationServiceTest`.

## Migration/compatibility implications

`V19__add_outbox_claiming_and_dead_letters.sql` adds five columns and backfills `status`/`next_attempt_at`/`dead_lettered_at` from existing `published_at`/`attempt_count` data; drops the now-superseded `ix_outbox_unpublished` partial index in favor of `ix_outbox_claimable`/`ix_outbox_claimed`/`ix_outbox_dead_lettered`. No data loss; every existing row is assigned a consistent status.

## Revisit criteria

This decision should be revisited when:

- a real message broker replaces the simulated publisher (its own ADR, per ADR 0009's original note, still applies);
- the new metrics are wired into a real alerting system (a separate, infrastructure-integration change);
- exactly-once delivery becomes a hard requirement (would need consumer-side deduplication or a different pattern entirely, not a change to this claim mechanism).

## Links

- Issue #23
- [ADR 0009](0009-outbox-relay-with-simulated-publisher.md) (superseded — simulated publisher port and configuration externalization remain unchanged and still apply), [ADR 0012](0012-pseudonymous-subject-tokens-for-integration-events.md) (account-reference redaction, unaffected)
- Tests: `OutboxBackoffCalculatorTest`, `OutboxRelayTest`, `OutboxClaimStoreConcurrencyTest`, `OutboxAdminApplicationServiceTest`, `OutboxAdminControllerTest`, `OutboxEventRetentionCleanupTest`, `OutboxEventIntegrationTest`
