# Executable architecture invariants

- Baseline: `main` at `f1bc7eb54604773861344e1b785172780510a1d7`
- Updated: 2026-07-24

This document records guarantees that future changes must preserve. An invariant is stronger than an implementation detail: it must remain true across retries, concurrent requests, migration changes, and refactoring.

## Enforcement levels

| Level | Meaning |
| --- | --- |
| Domain | Constructor, value object, aggregate, or application-service validation |
| Transaction | Multiple writes succeed or roll back as one operation |
| Lock | Pessimistic or optimistic concurrency control prevents unsafe races |
| Database | PostgreSQL constraint, index, foreign key, or trigger is the final authority |
| Contract | HTTP/event schema prevents caller-controlled authority or ambiguity |
| Test | Automated unit, architecture, integration, or concurrency verification |

An important invariant should normally have more than one enforcement level. Java validation alone is not sufficient for cross-process or direct-database races.

## Protection invariants

### P-01 — One idempotency key identifies one request fingerprint

A stored idempotency key cannot be reused for a materially different protection request.

**Enforced by:** domain fingerprinting, PostgreSQL uniqueness, conflict handling, integration tests.

**Known gap:** equivalent concurrent losers still require stable winner re-read semantics described in issue #22.

### P-02 — Equivalent completed retries return one logical decision

Sequential equivalent retries return the persisted result rather than creating another logical decision.

**Enforced by:** idempotency guard, persisted result reference, integration tests.

**Known gap:** full concurrent guarantee is tracked by issue #22.

### P-03 — Risk scores are bounded

Every persisted and returned risk score is between 0 and 100.

**Enforced by:** risk-domain validation, policy validation, database constraints where present, boundary tests.

### P-04 — Every decision records algorithm and policy provenance

A final decision identifies the exact algorithm version and policy key/version used.

**Enforced by:** decision result contract, audit trace persistence, integration tests.

### P-05 — A protection request has exactly one explicit outcome

The decision outcome is one of `ALLOW`, `REQUIRE_STEP_UP`, `START_RECOVERY`, or `TEMPORARILY_BLOCK`. Recovery authorization is not inferred from another outcome.

**Enforced by:** `ProtectionOutcome`, policy evaluation, ADR 0010, tests for standard and recovery-request contexts.

### P-06 — Recovery authorization is emitted only for START_RECOVERY

A standard event or a recovery event routed to another outcome cannot create a recovery authorization.

**Enforced by:** protection application service branch, recovery-event directive mapping, transactional listener, integration tests.

## Policy invariants

### PL-01 — Activated policy versions are immutable

An activated or retired policy version cannot have its thresholds or identity rewritten. Corrections require a new version.

**Enforced by:** policy lifecycle state machine, PostgreSQL trigger, integration tests.

### PL-02 — Routing thresholds are ordered and bounded

Thresholds must produce non-overlapping deterministic outcome regions within the score range.

**Enforced by:** command/value validation, policy lifecycle tests, database checks.

### PL-03 — Historical decisions do not change when policy activation changes

Activating or retiring another version affects future routing only. Existing traces preserve their recorded version.

**Enforced by:** immutable trace fields, version-addressable policy evaluation, replay tests.

## Audit and replay invariants

### A-01 — Decision traces are append-only evidence

Application code cannot update or delete a historical decision trace to reflect current policy or algorithm behavior.

**Enforced by:** audit module ownership, PostgreSQL immutability trigger, persistence integration tests.

### A-02 — Audit is not an operational recovery authority

Recovery initiation must not require `DecisionTraceQuery` or a foreign key to the audit projection. Existing unexpired authorization remains usable without a matching audit projection row.

**Enforced by:** module dependencies, `RecoveryAuthorization`, migration V10, integration test without audit row.

### A-03 — Replay is side-effect-free

Replay and shadow evaluation do not create challenges, recoveries, outbox events, idempotency records, or mutable audit state.

**Enforced by:** simulation application service boundaries and integration tests.

**Known gap:** full historical risk-algorithm reconstruction is tracked by issues #21 and #43.

## Challenge invariants

### C-01 — A challenge is purpose-bound

A challenge created for one purpose cannot authorize another purpose.

**Enforced by:** `ChallengePurpose`, verification/consumption commands, persisted purpose and context, tests.

### C-02 — A challenge is context-bound

Recovery identity proof must carry the exact recovery ID used at challenge creation. A challenge from another recovery cannot advance the flow.

**Enforced by:** challenge context ID, recovery confirmation checks, integration tests.

### C-03 — Successful challenge consumption is single-use

A consumed challenge cannot be consumed again to authorize a second transition.

**Enforced by:** terminal-state rules, challenge service, persistence state, tests.

**Known gap:** concurrent one-winner semantics and optimistic locking are tracked by issues #20 and #37.

### C-04 — Attempt budgets and expiry cannot be bypassed

Expired, exhausted, failed, or terminal challenges reject further verification or consumption.

**Enforced by:** challenge state machine, injected clock, attempt counters, unit/integration tests.

## Recovery authorization invariants

### RA-01 — Caller input is not recovery authority

The caller supplies only `authorizationId`. Account reference, recovery directive, risk score, protection request ID, and decision ID come from the persisted authorization.

**Enforced by:** HTTP request contract, `InitiateRecoveryCommand`, authorization aggregate, recovery service tests.

### RA-02 — Authorization fields are immutable

After issuance, authorization identity, account reference, directive, risk, issue time, and expiry cannot change.

**Enforced by:** immutable domain record, entity without mutators, PostgreSQL trigger, integration test.

### RA-03 — Authorization expires after its bounded validity window

An authorization is valid only before `expiresAt`. The baseline issuance TTL is 15 minutes.

**Enforced by:** protection issuance, authorization domain validation, locked consumption, integration tests.

### RA-04 — Authorization can be consumed only once

Only the first valid initiation may transition `consumedAt` from null. Consumption cannot be reversed or reassigned.

**Enforced by:** pessimistic lock, entity transition method, trigger, unique flow authorization constraint.

### RA-05 — Issuance is atomic with the originating decision

A `START_RECOVERY` response cannot commit without its authorization. Listener failure rolls back the protection decision transaction.

**Enforced by:** synchronous event publication, transactional listener, shared database transaction, application-context/integration verification.

## Recovery-flow invariants

### R-01 — One authorization creates at most one recovery flow

A recovery authorization cannot create multiple logical flows or multiple identity challenges.

**Enforced by:** authorization lock, unique `recovery_flow.authorization_id`, existing-flow lookup, integration retry test.

**Known gap:** dedicated multi-thread/process race coverage is tracked by issue #18.

### R-02 — Equivalent initiation retry returns the existing flow

Once an authorization has produced a flow, the same authorization returns that flow instead of a conflict or duplicate.

**Enforced by:** repository lookup before consumption and after observed prior consumption, integration tests.

### R-03 — Recovery classification gates remain enforced after identity proof

Verified identity does not bypass risk classification:

```text
0–30   -> IDENTITY_VERIFIED -> COMPLETED
31–60  -> DELAYED -> eligibleAfter -> COMPLETED
61–100 -> MANUAL_REVIEW -> approved/rejected
```

**Enforced by:** recovery state machine, persisted classification, clock checks, operator review path, boundary tests.

### R-04 — Manual review cannot be bypassed through public completion

A manual-review flow reaches `COMPLETED` only through explicit approval and reaches `REJECTED` only through explicit rejection.

**Enforced by:** application service state checks and integration tests.

### R-05 — Terminal recovery states cannot reopen

`COMPLETED`, `REJECTED`, and identity-failure terminal paths do not transition back to an active state.

**Enforced by:** state-machine checks and tests.

### R-06 — Recovery identifiers have distinct meanings

`authorizationId`, `protectionRequestId`, and `originatingDecisionId` must not be conflated:

- authorization ID is the operational credential;
- protection request ID correlates the originating operation;
- decision ID correlates immutable evidence.

**Enforced by:** separate domain fields, separate database columns, response contract, integration assertions.

## Outbox invariants

### O-01 — Event storage joins the business transaction

An outbox record representing a committed domain event is persisted with the originating business transaction.

**Enforced by:** transactional listener and PostgreSQL transaction boundary.

### O-02 — Delivery is at-least-once, not exactly-once

A crash after external publication but before acknowledgement may cause redelivery. Consumers must use stable event identity.

**Enforced by:** ADR 0009 documentation and stable event IDs.

**Known gap:** explicit claiming, backoff, and dead-letter lifecycle are tracked by issue #23.

## Data and API invariants

### D-01 — Passwords and real authentication secrets are forbidden

The project does not persist passwords, production MFA seeds, private keys, or real provider credentials.

**Enforced by:** project scope, data-classification baseline, code review, tests and future secret scanning.

### D-02 — Public existence-sensitive errors are non-enumerable

Recovery authorization failures do not distinguish missing, expired, consumed, or inconsistent internal state to the caller.

**Enforced by:** generic domain exception mapping and controller tests.

### D-03 — Raw database exceptions do not define the public contract

Constraint and stale-write failures must be translated to stable domain or RFC 9457 conflicts.

**Current status:** partially enforced. Remaining standardized mapping is tracked by issues #18, #36, and #37.

## Architecture invariants

### M-01 — Internal repositories and persistence entities do not cross module boundaries

Modules integrate through public APIs or domain events, never through another module's internal repository/entity package.

**Enforced by:** package layout, Spring Modulith verification, ArchUnit tests.

### M-02 — Network distribution requires evidence

A module is not extracted into a service solely to appear more scalable. Extraction requires independent scaling, ownership, deployment, governance, or failure-isolation evidence.

**Enforced by:** ADR 0001 and architecture review.

## Pull-request review checklist

Every behavior-changing PR should answer:

1. Which invariants does this change preserve, strengthen, add, or supersede?
2. What is the final concurrency authority: application check, lock, or database constraint?
3. What happens on an equivalent retry?
4. What happens on conflicting reuse?
5. Which fields are caller-controlled, authorization-owned, or evidence-only?
6. Can a transaction roll back after a success metric/event is emitted?
7. Which automated test proves the failure path and race boundary?
8. Which feature-catalog and ADR entries must change?
