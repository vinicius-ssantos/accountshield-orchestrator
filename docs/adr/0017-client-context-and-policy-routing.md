# ADR 0017: Client context and policy routing

- Status: Accepted
- Date: 2026-07-25

## Context

Issue #26 asked AccountShield to become a reusable decision platform for multiple client applications: idempotency keys, rate limits, and policy selection are effectively global today, and the decision service hardcodes a single `DEFAULT_POLICY_KEY = "account-protection-default"` regardless of caller. Confirmed via direct code inspection: `ProtectionDecisionCommand` had no client field, `protection.idempotency_record` had a bare `UNIQUE (idempotency_key)` constraint, the in-memory rate limiter bucketed by raw `accountReference`, and no `clientId`/`tenant`/`PolicyRouting` concept existed anywhere in `src/main`.

## Decision

### `ClientId` — an opaque, defaulted value type

`protection.ClientId(String value)` is a small validated record with a `ClientId.DEFAULT = new ClientId("default-client")` constant. Every mechanism below defaults to it when a caller omits a client id, which is what makes "existing single-client behavior remains supported" true throughout rather than a special case.

`ProtectionDecisionCommand` gains a trailing `ClientId clientId` on its canonical constructor, **with a compatibility constructor preserving the old 4-arg shape** (unlike #45, which broke `ProtectionDecisionCommand` call sites directly when it introduced `RiskSignalEnvelope`) — every existing test and integration call site keeps compiling unchanged.

### Idempotency scoped by `(client_id, idempotency_key)`

Migration `V17` adds `client_id VARCHAR(100) NOT NULL DEFAULT 'default-client'` to `protection.idempotency_record`, drops `uq_idempotency_key`, and adds `uq_idempotency_client_key UNIQUE (client_id, idempotency_key)`. **The `DEFAULT` clause is the single-tenant migration strategy** the issue asked to document: every pre-existing row is backfilled automatically to `'default-client'` by Postgres as part of the `ADD COLUMN`, with no separate backfill script. `IdempotencyGuard.resolve`/`record` gain a leading `clientId` parameter; `DatabaseIdempotencyGuard` looks up `findByClientIdAndIdempotencyKey`. Two clients can now reuse the exact same idempotency key without collision — the literal first acceptance criterion.

### Rate limiting scoped by `(clientId, accountReference)`

`ProtectionRateLimiter.checkLimit(ClientId, String, Instant)`; `InMemorySlidingWindowRateLimiter` keys its sliding-window map by a `record WindowKey(ClientId, String)` rather than string concatenation, avoiding any delimiter-collision edge case. No migration needed — this state was already in-memory and non-persistent (ADR 0008).

### `PolicyRoutingService` — minimal, read-only, and the key to "activation isolated by client"

New `policy.PolicyRoutingService.resolvePolicyKey(String clientId, String eventType)` — plain `String`s, not `protection.ClientId`/`ProtectionEventType`, because the module direction is `protection -> policy`, never the reverse (`docs/architecture/README.md`). Backed by a new `policy.client_policy_route` table (`client_id, event_type, policy_key`, `UNIQUE(client_id, event_type)`), seeded with one row per existing `ProtectionEventType` mapping `('default-client', <eventType>, 'account-protection-default')` — reproducing today's behavior exactly. `ProtectionDecisionApplicationService` now calls `policyRoutingService.resolvePolicyKey(...)` instead of using a hardcoded constant, making "decision requests route to event-specific policies" real.

**Key insight — no changes needed to the policy lifecycle module.** "Policy activation is isolated by client" does *not* require adding a `client_id` column to `policy_version` or touching #33/#46's freshly-built maker-checker lifecycle at all. `policy_key` is already an opaque string, and `uq_single_active_policy` (ADR 0007) is already scoped per-`policy_key`. Once the routing table hands different clients different `policy_key` values, activation isolation is a **pre-existing, free property** — nothing new to build. An unroutable client/event combination throws the existing `ActivePolicyUnavailableException` (reusing its established `503`/`ACTIVE_POLICY_UNAVAILABLE` fail-closed contract) rather than a new exception type.

### Propagation into audit and integration events

`protection_request` gains a `client_id` column (same `DEFAULT`-backfill migration strategy). `normalizedContext()` gains a `"clientId"` entry (same JSONB-extension pattern used for signal provenance and degradation in #44/#45). `ProtectionDecisionMade` gains a trailing `String clientId` field (record-only, no compat constructor — few call sites, same precedent as #44's `degraded`/`degradationReason`); because `OutboxEventRecorder` already converts this event generically via `objectMapper.convertValue(event, Map.class)`, `clientId` flows into the outbox integration event and the `accountshield.security` structured log automatically, with no new listener wiring.

## Alternatives considered

- **Adding `client_id` to `policy_version` directly** — rejected; the routing-table indirection already gives client-scoped activation isolation for free, and avoids re-touching the policy lifecycle machinery #33/#46 just finished building.
- **String-concatenated rate-limiter keys** (`clientId + ":" + accountReference`) — rejected in favor of a small `WindowKey` record, avoiding any theoretical delimiter-collision edge case.
- **A full CRUD/versioning lifecycle for `client_policy_route`** mirroring policy versions — rejected as disproportionate; a routing entry is a simple mapping, not a governed artifact requiring draft/validate/approve/activate.

## Consequences

### Positive

- two clients can reuse the same account reference and idempotency key independently (verified by `ProtectionDecisionIntegrationTest.twoClientsReusingTheSameAccountReferenceAndIdempotencyKeySucceedIndependently`);
- policy activation isolation by client falls out of the existing DB constraint with zero policy-module changes;
- every existing caller (a `ProtectionDecisionCommand` or entity construction call site) keeps compiling unchanged thanks to the `ClientId.DEFAULT`-based compatibility constructor and migration `DEFAULT` clauses.

### Negative

- cross-client replay/recovery access-control **enforcement** is not implemented — recovery and replay have no caller-identity/authorization concept to check against today; this PR propagates the `client_id` data a future authorization layer would need, but does not add enforcement, which is a separate, larger authorization-model decision;
- `client_policy_route` has no CRUD/versioning lifecycle — adding, changing, or removing a route today requires a migration or direct SQL, not an API call;
- simulation-module client-awareness ("prevent cross-client... simulations") is not addressed.

## Guardrails

- `policy.PolicyRoutingService`'s public contract uses plain `String`s, never `protection.ClientId`/`ProtectionEventType` — enforced by the existing Spring Modulith architecture test, which would fail if `policy` imported anything from `protection`;
- every new column added by migration `V17` carries a `DEFAULT` so no existing row is ever left in a NOT-NULL-violating state;
- an unroutable client/event combination fails closed via the existing `ActivePolicyUnavailableException`, never falls back to a default policy silently.

## Revisit criteria

This decision should be revisited when:

- cross-client replay/recovery access control needs real enforcement — requires a broader authorization-model decision (which callers may see which clients' data);
- `client_policy_route` needs its own governed lifecycle (versioning, approval) rather than a simple seeded mapping;
- simulation needs client-awareness.

## Links

- Issue #26
- [ADR 0007](0007-policy-lifecycle-state-machine.md) (the `uq_single_active_policy` constraint this reuses), [ADR 0008](0008-in-memory-rate-limiting.md) (rate limiting baseline), [ADR 0003](0003-idempotency-via-caller-key-and-fingerprint.md) (idempotency contract this extends)
- Tests: `ClientIdTest`, `DatabasePolicyRoutingServiceTest`, `DatabaseIdempotencyGuardTest`, `InMemorySlidingWindowRateLimiterTest`, `ProtectionDecisionApplicationServiceTest`, `ProtectionDecisionIntegrationTest`
