# ADR 0018: Claim idempotency before work, not record after work

- Status: Accepted
- Date: 2026-07-25

## Context

Issue #22 named two related problems in `ProtectionDecisionApplicationService`/`DatabaseIdempotencyGuard`, both confirmed by direct code inspection:

- **Implementation leakage**: the application service downcast the `IdempotencyGuard` port to its one concrete adapter — `idempotencyGuard instanceof DatabaseIdempotencyGuard dbg ? dbg.resourceType() : "protection_decision"` — to read a resource-type constant, and separately hardcoded its own 24-hour TTL literal (`now.plus(Duration.ofHours(24))`) while the adapter had an unused `defaultExpiry()` helper nobody called. Two independent, silently-inconsistent copies of the same policy.
- **The real concurrency bug**: `resolve()` ran early as a fast-path check, but `record()` ran at the very *end* of `decide()` — after `protection_request`, `decision_trace`, challenge creation, and event publishing had all already happened. Two truly concurrent, equivalent requests both passed `resolve()` (neither could see the other yet), both did the *entire* decision, and only at the final `record()` did the loser hit a unique-constraint violation — which `DatabaseIdempotencyGuard.record()` **unconditionally** turned into `ConflictingIdempotencyRequestException`, even when the loser's fingerprint was identical to the winner's. The loser's redundant `protection_request`/`decision_trace`/challenge/outbox rows were already committed by the time the conflict was discovered.

## Decision

### Claim first, finalize last

The port collapses `resolve()`/`record()` into `claim(clientId, idempotencyKey, fingerprint, resourceId, now)` and `finalizeResult(clientId, idempotencyKey, responsePayload)`. `claim()` is now the **first** database write in `decide()`, before `protection_request`, `decision_trace`, challenge creation, or any other side effect. `resourceType` and TTL are no longer parameters at all — they live entirely inside `DatabaseIdempotencyGuard` (a private constant and an injected `@Value` duration), which is what "move resource type and TTL policy behind the public idempotency contract" means concretely. Because the claim happens before any other write, a losing thread never creates a `protection_request`/`decision_trace`/challenge row at all — "only one of each" is now structural, not eventually-consistent.

### `INSERT ... ON CONFLICT DO NOTHING`, not insert-then-catch

`IdempotencyRecordRepository.insertIfAbsent(...)` is a native `@Modifying` query doing `INSERT ... ON CONFLICT (client_id, idempotency_key) DO NOTHING`, returning 0 or 1 affected rows. This relies on a specific, well-defined Postgres guarantee: a conflicting `INSERT` against a row another transaction is still holding (uncommitted) **blocks** until that transaction resolves, rather than racing to see who commits first. So when a losing thread's `insertIfAbsent` finally returns `0`, the winning row is *guaranteed* to be fully committed — including its `finalizeResult()` update, which runs inside the same transaction before commit. The loser can safely re-read and return the winner's real, complete payload. No raw `DataIntegrityViolationException` (or any other JDBC/Spring exception) ever leaves `DatabaseIdempotencyGuard` — the constraint is never actually violated from JPA's point of view, since `ON CONFLICT DO NOTHING` absorbs it.

### Distinguishing hit / race / conflict / expired

After a `0`-row insert, the guard re-reads the row:
- **expired** (`expires_at < now`): delete the stale row and retry the insert once, so an old key can be legitimately reused without ever surfacing a spurious conflict;
- **fingerprint differs**: `ConflictingIdempotencyRequestException` (`CONFLICT`);
- **fingerprint matches, payload present**: return the winner's result (`HIT` or `RACE` — see below);
- **fingerprint matches, payload still `NULL`**: see Guardrails — this is not a normal race, and is handled distinctly.

**HIT vs. RACE is a best-effort metric split only**, not a correctness distinction: both return the same duplicate result. A conflicting insert that resolves near-instantly reflects a row that was already fully settled before this call even started (`HIT`); one that took noticeably longer was blocked behind a concurrent, still-in-flight transaction (`RACE`). Implemented as a small elapsed-wall-clock-time heuristic around the insert call (threshold: 25ms) — deliberately approximate, since precise detection would require an additional claimed-at/finalized-at timestamp pair that no acceptance criterion asks for.

### The `NULL`-payload edge case

A `0`-row insert whose existing row has a matching fingerprint but a still-`NULL` payload does **not** mean "still executing right now" — a genuinely in-flight transaction would have *blocked* this insert, not let it return immediately. It means a prior attempt claimed this key and never called `finalizeResult()` — in practice, only reachable if the process crashed between the two calls (both run inside the same Spring-managed transaction, so an ordinary exception anywhere in `decide()` rolls back the claim along with everything else — this case requires the JVM or DB connection to die mid-commit, not an application-level failure). The guard fails closed here (`ConflictingIdempotencyRequestException`) rather than either silently redoing the work (risking a second real side-effecting decision) or fabricating a payload. The row is purged like any other expired claim once its TTL passes, after which a fresh attempt with the same key legitimately proceeds.

### Metrics and bounded-batch cleanup

New counter `accountshield.protection.idempotency`, tag `outcome` ∈ `{MISS, HIT, RACE, CONFLICT, EXPIRED}`, incremented directly in `DatabaseIdempotencyGuard` (same direct-build-and-increment style as `ProtectionDecisionApplicationService.degradedCounter()`). New `IdempotencyRecordRetentionCleanup` mirrors the existing `ChallengePlanRetentionCleanup`/`RecoveryFlowRetentionCleanup` `@Scheduled` shape, but — unlike those two, which delete unboundedly — loops a native, `LIMIT`-bounded delete (`accountshield.protection.idempotency.retention.batch-size`, default 500) until a batch returns fewer rows than the batch size, capped at 100 batches per tick to bound worst-case work on a large backlog.

## Alternatives considered

- **Insert-then-catch `DataIntegrityViolationException`** (the prior approach) — rejected; it's exactly the bug this ADR fixes: the exception carries no information about *when* the conflicting row became visible, so there's no way to distinguish a race from a genuine conflict, and it surfaces a raw JDBC-translated exception up through layers that shouldn't need to know about it.
- **A precise race/hit distinction via an extra timestamp pair** — rejected as disproportionate; not required by any acceptance criterion, and the elapsed-time heuristic is sufficient for the observability goal (seeing that races happen at all).
- **A saga/compensation mechanism for the claim-without-finalize crash window** — rejected; the window only exists across a JVM/connection crash (not any ordinary exception path, which the transaction boundary already unwinds), and the row self-heals via the existing TTL/cleanup job.

## Consequences

### Positive

- eight or more equivalent concurrent requests now all receive the identical real result, not seven conflicts and one winner;
- exactly one `protection_request`, `decision_trace`, and outbox row are created per logical decision, structurally, not by chance;
- the application service depends only on the public `IdempotencyGuard` port — no downcasting, no duplicated TTL literal;
- expired rows are now actually cleaned up, in bounded batches.

### Negative

- the elapsed-time HIT/RACE split is a heuristic, not a precise measurement, and could misclassify under unusual scheduler/GC pauses — acceptable since it only affects a metric tag, never behavior;
- a JVM/DB-connection crash between `claim()` and `finalizeResult()` leaves a key unusable until its TTL expires — accepted as a rare, self-healing edge case rather than solved with distributed-transaction machinery.

## Guardrails

- `ProtectionDecisionApplicationService` calls only `claim`/`finalizeResult` on the `IdempotencyGuard` interface — no `instanceof`, no downcast;
- `claim()`'s insert is always the first database write in `decide()`, before `protection_request`/`decision_trace`/challenge/outbox;
- no `DataIntegrityViolationException` or other raw JDBC/Spring exception type is ever thrown by `DatabaseIdempotencyGuard` — verified by `DatabaseIdempotencyGuardTest` asserting only `ConflictingIdempotencyRequestException` or a valid `IdempotencyResult` ever results from `claim()`.

## Revisit criteria

This decision should be revisited when:

- the claim-without-finalize crash window needs a stronger guarantee than "wait for TTL expiry" (e.g. a heartbeat/lease mechanism);
- precise race/hit metrics become operationally necessary rather than merely informative.

## Links

- Issue #22
- [ADR 0003](0003-idempotency-via-caller-key-and-fingerprint.md) (the original idempotency contract this strengthens), [ADR 0017](0017-client-context-and-policy-routing.md) (the `(client_id, idempotency_key)` uniqueness this builds on)
- Tests: `DatabaseIdempotencyGuardTest`, `IdempotencyConcurrencyTest`, `IdempotencyIntegrationTest`, `IdempotencyRecordRetentionCleanupTest`, `ProtectionDecisionApplicationServiceTest`
