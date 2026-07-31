# ADR 0045: Privacy-minimized outbox operator search read API

- Status: Accepted
- Date: 2026-07-30
- Related issues: #74, #184
- Related ADRs: 0023, 0040, 0041, 0042, 0043, 0044

## Context

The only existing outbox read surface is `GET /api/v1/outbox` (`OutboxAdminController`, `SECURITY_OPERATOR`-gated): a single-status list capped server-side, backed by a nine-field `OutboxEventSummary`. It has no multi-status filter, no event-type/age/attempt-count filters, and no aggregate health. Point-in-time health today exists only as two Micrometer `Gauge`s (`OutboxMetrics`: dead-lettered count, oldest-pending age) reachable via `/actuator/prometheus` under `OBSERVABILITY_READER` — a different role than the operator-console role, and not JSON.

Dead-letter diagnosis is structurally unsafe to expose as-is: `OutboxEventEntity.last_error` (written identically by `OutboxClaimStore.markFailedWithBackoff`/`markDeadLettered`) holds `ex.getMessage()` falling back to `ex.getClass().getSimpleName()` (`OutboxRelay.boundError`) — raw, unstructured exception text. No separate, safe failure-category field existed before this change.

`correlationId` (`IntegrationEventEnvelope`, ADR 0023) is not a separate entity column; it is written into the JSON `payload` at envelope-construction time and is exactly `aggregateId` (`OutboxEventRecorder.record`: `new IntegrationEventEnvelope(eventId, IntegrationEventSchema.CURRENT_VERSION, aggregateId, occurredAt, payload)`).

## Decision

AccountShield exposes one read-only operation, owned directly by the `outbox` module (the data is entirely its own — no cross-module composition, unlike `investigation`'s timeline/replay/policy-investigate endpoints, ADR 0041/0043/0044):

```text
POST /api/v1/operator/outbox/search
```

Aggregate health and the filtered event page are returned together in one response, backed by a new public port `outbox.OutboxOperatorQuery`, implemented by `outbox.internal.JdbcOutboxOperatorQuery` (`NamedParameterJdbcTemplate`, following the same raw-SQL, non-JPA style `audit.internal.JdbcDecisionInvestigationQuery` already established for privacy-minimized search read models). An operator triaging delivery health always needs both signals together, and combining them avoids two racing snapshots from two separate calls.

### Health block

`pendingCount` (`PENDING`, `attemptCount = 0`), `retryingCount` (`PENDING`, `attemptCount > 0` — distinct from `pendingCount`), `inProgressCount` (claimed/dispatching), `deadLetteredCount` (all-time), `oldestPendingAgeSeconds` (nullable — `null`, not `0`, when nothing is pending; reuses the same `MIN(occurred_at) WHERE status = 'PENDING'` computation `OutboxMetrics` already performs), `recentlyDeadLetteredCount`/`recentlyPublishedCount` (windowed counts over a fixed, explicitly-returned `windowMinutes`, rather than a fabricated "rate" unit), and `asOf` (when the summary was computed, so the frontend reasons about its own fetch staleness for an always-live query instead of the backend inventing a stale/fresh flag).

### Event page

Cursor-paginated (opaque `(occurredAt, id)` keyset, same encoding `JdbcDecisionInvestigationQuery` uses), filterable by status(es), exact event type, occurred-time window (`MAX_TIME_WINDOW` 31 days, same bound as `DecisionInvestigationQuery`), and attempt-count range. Each record carries `nextAttemptAt` only for `PENDING`/`IN_PROGRESS` rows — the column is never cleared on `PUBLISHED`/`DEAD_LETTERED` transitions, so it is nulled at the read boundary for terminal states rather than shown stale; `claimed`/`claimedAt` (claim state and timing only — `claimedBy`, the worker/instance identifier, is not exposed, it is not part of what an operator needs to see); `schemaVersion` (parsed from the envelope JSON, `null` on any historical row that predates envelope wrapping or fails to parse); `maskedCorrelationReference` (masked `aggregateId`, reusing the exact masking convention `PolicyInvestigationService` established: `"••••" + last 4 characters`).

### A safe, structured failure category alongside the existing raw message

`outbox.outbox_event` gains `last_error_category VARCHAR(200)` (migration `V29`). `OutboxRelay.dispatchSingle` already computes `ex.getClass().getSimpleName()` for logging; that same value is now threaded through `OutboxClaimStore.markFailedWithBackoff`/`markDeadLettered` into the new column. It is a real value computed at the actual failure site (e.g. `ConnectException`, `TimeoutException`), stored separately from the free-text `last_error` column specifically so it can safely cross the API boundary — `deadLetterFailureCategory` is only populated (and `deadLetterReasonAvailable` only `true`) for `DEAD_LETTERED` rows; `last_error` itself is never read by this query.

## Alternatives considered

### Extend the existing `GET /api/v1/outbox` endpoint in place

Rejected. It mixes read and the `requeue` mutation under one path prefix and one GET+query-param convention, unlike the established body-based POST convention (ADR 0040–0044) that keeps filter values out of URLs. Extending it in place would also require a breaking response-shape change for existing callers of `OutboxEventSummary`. The existing endpoint and its role/route are untouched; this is additive.

### Separate `/search` and `/health` endpoints

Rejected for the same reason ADR 0044's directory/investigate split does *not* apply here: unlike policy search-then-investigate (two genuinely different questions), an operator viewing outbox delivery state needs health and the record list together on first load — one round trip avoids two snapshots from racing.

### Parse a real failure taxonomy from `last_error`

Rejected. `last_error` collapses `ex.getMessage()` (or the class name, if no message) into one bounded string at write time — the two cannot be reliably separated after the fact, and any taxonomy inferred from free text would be fabricated pattern-matching, not a real computed value. Capturing `ex.getClass().getSimpleName()` at the actual failure site, before it is ever combined with message text, is the only approach that is both safe and not invented after the fact.

### Expose `claimedBy` (the claiming worker/instance identifier)

Rejected as unnecessary for this issue's ask ("claimed state," not claimant identity) and as avoidable internal-topology exposure; `claimed`/`claimedAt` already answer "is this currently being processed, and since when."

## Consequences

### Positive

- frontend #74 gets aggregate health and a filterable, paginated event list in a single, minimized, `no-store` call;
- dead-letter diagnostics are informative without ever crossing a raw exception message or stack trace;
- `oldestPendingAgeSeconds` being `null` (not `0`) when nothing is pending keeps "stale/unavailable" distinguishable from "zero activity," consistent with this codebase's established `AVAILABLE`/`NOT_APPLICABLE`/`UNAVAILABLE` discipline;
- the existing `/api/v1/outbox/**` admin surface (list + requeue) is completely untouched.

### Negative

- `last_error_category` is a second, narrower error-shape field alongside the pre-existing `last_error`, which a future reader must know is the only one safe to expose;
- the health block is a single global aggregate with no per-caller scoping — acceptable for an operator console with no per-tenant outbox partitioning today, but would need revisiting if the outbox ever became multi-tenant-scoped.

## Executable guardrails

- `SecurityIntegrationTest` covers missing authentication, wrong role, and `SECURITY_OPERATOR` success;
- `JdbcOutboxOperatorQueryTest` (PostgreSQL) verifies event-type-filtered pagination ordering, attempt-count range filtering, dead-letter failure-category/masking/`nextAttemptAt`-nulling, claimed-state reporting, health-count deltas across all buckets, empty results, and a malformed-cursor rejection;
- `OutboxOperatorControllerTest` verifies the `no-store` header, that `lastError`/`payload`/`claimedBy` never appear in the response body, and that a `DataAccessException` never leaks its message;
- `OutboxRelayTest` verifies `ex.getClass().getSimpleName()` is threaded through to both acknowledgement paths;
- `ArchitectureTest` (Spring Modulith boundary verification) passes with no new cycle — this endpoint adds no new module dependency, since it is entirely within `outbox`.

## Migration/compatibility implications

`V29__add_outbox_operator_search_support.sql` adds one nullable column (`last_error_category`) and five indexes supporting the new keyset-paginated, multi-filter search and the windowed health counts. No data loss; existing rows simply have a `null` category until their next failure/dead-letter transition.

## Revisit criteria

Revisit this decision if:

- the outbox ever becomes multi-tenant-scoped, requiring the health block to be scoped per caller rather than global;
- a real message broker (ADR 0009's original revisit note) changes how failures are categorized upstream, making `ex.getClass().getSimpleName()` less informative than a broker-native error code;
- the frontend needs a genuinely separate health-only or events-only fetch path (e.g. independent polling cadences), which would argue for splitting this single endpoint.
