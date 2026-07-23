# ADR 0005: Recovery flow state machine with risk-based classification

- Status: Accepted
- Date: 2026-07-22
- Updated: 2026-07-23

## Context

Account recovery is one of the highest-risk account-protection flows. A successful identity proof is necessary, but it is not sufficient to authorize immediate completion. The persisted recovery classification must control the state reached after identity verification.

A recovery attempt is a multi-step state machine with a purpose-bound identity challenge, optional delay, and manual review. Allowing every verified challenge to reach the same completion-ready state would make the `DELAYED` and `MANUAL_REVIEW` classifications informational only and would permit a documented security gate to be bypassed.

## Decision

The `recovery` module owns a deterministic state machine backed by PostgreSQL. The risk score stored in the originating decision trace is classified once during initiation and persisted on the recovery flow.

### Risk classification

| Classification | Risk score | Post-verification state | Completion authority |
| --- | ---: | --- | --- |
| `IMMEDIATE` | 0–30 | `IDENTITY_VERIFIED` | public completion operation |
| `DELAYED` | 31–60 | `DELAYED` | public completion operation at or after `eligibleAfter` |
| `MANUAL_REVIEW` | 61–100 | `MANUAL_REVIEW` | explicit operator review only |

The current thresholds are domain constants. Score boundaries `30`, `31`, `60`, and `61` are executable integration-test fixtures.

### State machine

```text
initiate
  -> VERIFYING_IDENTITY

VERIFYING_IDENTITY
  -> IDENTITY_FAILED     challenge fails or expires
  -> IDENTITY_VERIFIED   verified identity + IMMEDIATE
  -> DELAYED             verified identity + DELAYED
  -> MANUAL_REVIEW       verified identity + MANUAL_REVIEW

IDENTITY_VERIFIED
  -> COMPLETED           complete

DELAYED
  -> COMPLETED           complete at or after eligibleAfter

MANUAL_REVIEW
  -> COMPLETED           approved review
  -> REJECTED            rejected review
```

`COMPLETED`, `REJECTED`, and `IDENTITY_FAILED` are terminal. Identity confirmation is accepted only from `VERIFYING_IDENTITY`. Review is accepted only from `MANUAL_REVIEW`. A rejected or completed flow cannot be reopened.

Equivalent calls to `complete` on an already completed flow return the existing completed representation and do not emit a second logical completion.

### Identity challenge binding and consumption

Initiation creates a `RECOVERY_IDENTITY` challenge bound to:

- the opaque account reference;
- the recovery flow ID as `contextId`;
- the recovery identity purpose.

The caller first verifies the challenge proof. Recovery confirmation then consumes that exact verified challenge. Consumption changes `VERIFIED` to `CONSUMED` exactly once. A challenge created for another recovery, purpose, account, or context cannot advance the flow.

Identity proof consumption and recovery classification are separate invariants:

1. the challenge proves and authorizes identity confirmation once;
2. the persisted classification selects the only valid post-verification state;
3. the selected state determines who or what may complete the recovery.

### Delay semantics

`eligibleAfter` is calculated during initiation as the injected UTC clock plus fifteen minutes for `DELAYED` flows. It remains `null` for `IMMEDIATE` and `MANUAL_REVIEW` classifications.

A delayed flow cannot complete while the current clock is before `eligibleAfter`. At or after that instant, the public completion operation may transition the flow to `COMPLETED`.

### Manual-review semantics

A manual-review flow never becomes completion-ready through the public `complete` operation. Only the review operation may transition it:

- `APPROVE` -> `COMPLETED`;
- `REJECT` -> `REJECTED`.

Operator authentication and role enforcement are tracked separately. This ADR defines the state-machine invariant independent of the authentication adapter.

### Persistence

Recovery flows live in `recovery.recovery_flow`. The persisted record includes:

- originating protection request ID;
- opaque account reference;
- recovery event type;
- risk score and classification;
- identity challenge ID;
- current status;
- initiation, update, and eligibility timestamps;
- reviewer when applicable.

The database is the source of truth for every transition. Public responses are projections of the persisted state.

### API behavior

```text
POST /api/v1/recovery                         initiate
POST /api/v1/recovery/{id}/confirm-identity   consume identity proof and enter classification gate
POST /api/v1/recovery/{id}/complete           complete immediate/eligible delayed flow
POST /api/v1/recovery/{id}/review             approve or reject manual review
```

Invalid transitions return a stable conflict response. Authorization-sensitive failures remain generic so callers cannot enumerate internal state through challenge or recovery identifiers.

## Consequences

### Positive

- `DELAYED` and `MANUAL_REVIEW` are executable security gates rather than metadata;
- successful identity verification cannot bypass waiting or operator approval;
- recovery challenge proof is purpose-bound and single-use;
- completion of immediate and delayed flows remains deterministic;
- terminal states cannot be reopened;
- exact threshold behavior is covered end to end with PostgreSQL and the real challenge service.

### Negative

- delayed completion requires a later retry or future scheduler;
- manual review remains an operational dependency;
- thresholds are still hard-coded and their versioned provenance is deferred;
- operator authentication and authorization are not addressed by this decision.

## Guardrails

- risk score is bounded from 0 through 100;
- post-verification state is derived only from the persisted classification;
- `complete` rejects `MANUAL_REVIEW` regardless of elapsed time;
- `review` rejects every state other than `MANUAL_REVIEW`;
- `confirmIdentity` rejects every state other than `VERIFYING_IDENTITY`;
- delayed completion checks the injected UTC clock against `eligibleAfter`;
- challenge purpose, context, account, and one-time consumption are enforced by the challenge module;
- integration tests execute `initiate -> verify challenge -> confirm identity -> complete/review`.

## Revisit criteria

Revisit this ADR when:

- recovery classification rules become versioned policy artifacts;
- an explicit recovery authorization replaces the audit read model;
- a scheduler automatically advances eligible delayed flows;
- authenticated operator principals replace reviewer strings;
- recovery cooldown, notification, or velocity controls are introduced.
