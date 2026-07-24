# ADR 0010: Recovery trust boundaries and explicit authorization

- Status: Accepted
- Date: 2026-07-23
- Updated: 2026-07-24

## Context

Recovery must not trust a caller to supply risk, account identity, recovery type, or proof from another flow. The first hardening step required an explicit `START_RECOVERY` decision, but recovery still queried the audit decision-trace read model at initiation time.

Audit is evidence and explainability infrastructure. Treating it as an operational authorization source couples recovery availability and consistency to a projection that should not decide whether a security-sensitive command may proceed.

## Decision

### Explicit protection outcome

Only recovery-request protection events may produce `START_RECOVERY`:

- `LOGIN_RECOVERY_ATTEMPT`;
- `PASSWORD_RESET_ATTEMPT`;
- `CREDENTIAL_CHANGE_ATTEMPT`;
- `DEVICE_TRUST_RESET_ATTEMPT`.

The policy engine evaluates those events with recovery context. Scores through the versioned `recoveryMaxScore` produce `START_RECOVERY`; higher scores produce `TEMPORARILY_BLOCK`. Standard events retain `ALLOW`, `REQUIRE_STEP_UP`, and `TEMPORARILY_BLOCK` routing.

### RecoveryAuthorization aggregate

A `START_RECOVERY` decision issues an immutable `RecoveryAuthorization` containing:

- authorization ID;
- protection request ID;
- decision ID;
- opaque account reference;
- recovery directive;
- risk score;
- issue time;
- expiry time;
- optional consumption time.

Protection publishes `RecoveryAuthorizationIssued` synchronously. The recovery module persists it in the same transaction as the protection decision. A failure to persist the authorization rolls back the decision, audit trace, and idempotency record together.

The protection decision response exposes `recoveryAuthorizationId`. `POST /api/v1/recovery` accepts only this identifier.

### Audit is evidence, not authority

Recovery initiation does not import or query `DecisionTraceQuery`. It derives account reference, directive, risk, protection request ID, and decision ID exclusively from the persisted authorization.

The flow retains request and decision IDs for correlation and evidence, but the database no longer requires an operational foreign key from the recovery flow to the audit projection. A previously issued authorization remains usable when the audit projection is unavailable or absent.

### Expiration and consumption

Authorizations expire after 15 minutes. Consumption:

- locks the authorization row pessimistically;
- rejects missing and expired IDs with the same generic response;
- sets `consumedAt` once;
- cannot mutate any other authorization field;
- cannot be reversed or consumed by a second flow.

PostgreSQL enforces one recovery flow per authorization. Equivalent retries return the existing flow and do not create another identity challenge.

### Caller cannot choose recovery attributes

The caller supplies only `authorizationId`. Recovery event type, account reference, and risk score are authorization-owned values. Any compatibility constructor accepting a caller event type deliberately ignores it.

### Challenge continuation

Initiation creates a `RECOVERY_IDENTITY` challenge bound to account, recovery ID, and purpose. Identity confirmation consumes that exact verified challenge once before entering the classification gate defined by ADR 0005.

### Replay

The decision trace still records protection context for explainability. Deterministic replay restores the recovery policy context, but replay does not issue or consume operational authorizations.

## Consequences

### Positive

- audit outages or projection lag cannot block an already authorized recovery;
- `ALLOW`, `REQUIRE_STEP_UP`, and `TEMPORARILY_BLOCK` cannot authorize recovery;
- callers cannot substitute account, risk, or recovery event type;
- authorization issuance is atomic with the originating decision;
- expiration limits the usable authorization window;
- pessimistic locking and unique constraints enforce single consumption under concurrency;
- equivalent retries are idempotent and return the same flow;
- missing and expired authorizations remain non-enumerable.

### Negative

- protection decisions that start recovery now create an additional transactional row;
- protection and recovery communicate through a synchronous domain event;
- authorization retention and cleanup require an operational policy;
- idempotency beyond authorization-based equivalent retries remains separate work.

## Guardrails

- only `START_RECOVERY` emits an authorization;
- authorization TTL is 15 minutes;
- immutable fields are protected by a PostgreSQL trigger;
- `consumedAt` permits only the first transition from null;
- a unique foreign key binds one flow to one authorization;
- challenge creation and authorization consumption share the recovery transaction;
- public authorization failures use one generic problem detail;
- audit identifiers remain correlation evidence, not execution authority;
- Spring Modulith access remains through public module events and APIs.

## Revisit criteria

Revisit when authorization cleanup or archival is introduced, when TTL must vary by policy or directive, or when authorizations must be consumed across independently deployed services.
