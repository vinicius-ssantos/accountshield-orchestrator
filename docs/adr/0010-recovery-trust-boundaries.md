# ADR 0010: Recovery trust boundaries and explicit authorization

- Status: Accepted
- Date: 2026-07-23
- Updated: 2026-07-23

## Context

Recovery must not trust a caller to supply risk, account identity, recovery type, or proof from another flow. A prior hardening step derived risk and account data from the immutable decision trace and bound the identity challenge to the recovery flow, but any decision outcome could still be used to initiate recovery.

That meant `ALLOW`, `REQUIRE_STEP_UP`, and `TEMPORARILY_BLOCK` traces were acting as recovery authorizations even though none explicitly granted that capability.

## Decision

### Explicit protection outcome

Add `START_RECOVERY` to `ProtectionOutcome`. Only a trace with this exact outcome may initiate recovery.

Recovery-request protection events are explicit:

- `LOGIN_RECOVERY_ATTEMPT`;
- `PASSWORD_RESET_ATTEMPT`;
- `CREDENTIAL_CHANGE_ATTEMPT`;
- `DEVICE_TRUST_RESET_ATTEMPT`.

The policy engine evaluates those events with `PolicyEvaluationContext.recoveryRequestContext()`. Scores through the versioned `recoveryMaxScore` produce `START_RECOVERY`; higher scores produce `TEMPORARILY_BLOCK`. Standard events retain `ALLOW`, `REQUIRE_STEP_UP`, and `TEMPORARILY_BLOCK` routing.

### Caller cannot choose recovery type

`InitiateRecoveryCommand` is authoritative only for `protectionRequestId`. `RecoveryEventType` is derived from the originating trace's persisted `protectionEventType`.

A compatibility constructor may accept an event type, but the value is deliberately ignored and cannot influence the flow.

### Single-use authorization

Each recovery persists `originatingDecisionId`. PostgreSQL enforces:

- a foreign key to `audit.decision_trace`;
- non-null originating decision;
- a unique recovery per originating decision.

The application performs a pre-check and maps both duplicate and incompatible authorization cases to the same generic rejection. The database unique constraint is the final concurrency authority.

### Challenge continuation

Initiation creates a `RECOVERY_IDENTITY` challenge bound to account, recovery ID, and purpose. Identity confirmation consumes that exact verified challenge once before entering the classification gate defined by ADR 0005.

### Replay

The decision trace records `protectionEventType` and `recoveryRequest`. Deterministic replay restores the same policy evaluation context so a historical `START_RECOVERY` decision is not re-evaluated as a standard event.

## Consequences

### Positive

- `ALLOW`, `REQUIRE_STEP_UP`, and `TEMPORARILY_BLOCK` cannot authorize recovery;
- callers cannot substitute a different recovery event;
- one decision cannot start multiple recovery flows;
- the response and persisted recovery expose both protection request and decision identifiers;
- missing, incompatible, and consumed authorization cases remain non-enumerable;
- standard protection behavior remains backward-compatible.

### Negative

- the current recovery authorization still depends on the audit read model;
- policy definitions gain a recovery-specific threshold;
- historical policy version `1.0.0` remains immutable and is retired in favor of recovery-capable version `1.1.0`;
- idempotent duplicate initiation returning the existing flow is deferred to dedicated recovery-idempotency work.

## Guardrails

- only `START_RECOVERY` is accepted;
- event derivation uses the immutable trace, never caller input;
- `originatingDecisionId` is mandatory and unique;
- invalid authorization responses use one generic public problem detail;
- challenge creation and recovery persistence share one transaction;
- replay restores recovery evaluation context;
- Spring Modulith access remains through public module APIs.

## Revisit criteria

Revisit when an explicit `RecoveryAuthorization` aggregate replaces audit as the authorization source, when expiration is introduced, or when duplicate equivalent initiation must return the existing recovery.
