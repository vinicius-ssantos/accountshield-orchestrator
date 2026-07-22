# ADR 0005: Recovery flow state machine with risk-based classification

- Status: Accepted
- Date: 2026-07-22

## Context

Account recovery is one of the highest-risk flows in account protection. An attacker who can bypass recovery can take over an account without the password. The platform must provide a recovery flow that is explicit, risk-aware, and resistant to abuse.

A recovery attempt is not a single decision — it is a multi-step state machine with identity verification, optional delays, and manual review. The classification of risk determines whether the flow proceeds immediately, is delayed, or requires human intervention.

The `challenge` module already provides identity-verification challenges. Recovery must orchestrate challenge creation and verification as part of its own state machine, without coupling to challenge internals.

## Decision

Introduce a `recovery` module with its own PostgreSQL schema and a deterministic state machine. Recovery flows are classified by risk score into three tiers that control the path through the state machine.

### State machine

```
INITIATED -> VERIFYING_IDENTITY    (identity challenge created)
VERIFYING_IDENTITY -> IDENTITY_CONFIRMED  (challenge verified)
VERIFYING_IDENTITY -> IDENTITY_FAILED     (challenge failed or expired)
IDENTITY_CONFIRMED -> COMPLETED    (immediate classification only, low risk)
IDENTITY_CONFIRMED -> DELAYED      (delayed classification, waiting for eligibility)
DELAYED -> COMPLETED               (delay elapsed, auto-completable)
DELAYED -> MANUAL_REVIEW           (delay elapsed, flagged for review)
MANUAL_REVIEW -> COMPLETED         (operator approves)
MANUAL_REVIEW -> REJECTED          (operator rejects)
```

Terminal states: `COMPLETED`, `REJECTED`, `IDENTITY_FAILED`.

### Risk classification

| Classification | Risk score range | Behavior |
| --- | --- | --- |
| `IMMEDIATE` | 0–30 | Proceeds to `COMPLETED` after identity confirmation |
| `DELAYED` | 31–60 | Enters `DELAYED` with `eligibleAfter` timestamp |
| `MANUAL_REVIEW` | 61–100 | Enters `MANUAL_REVIEW` after delay |

The classification thresholds are domain constants, not configurable per request.

### Integration with challenge

The `recovery` module calls `ChallengeService.verifyIdentityForRecovery(UUID challengeId)` — a read-only method that returns the challenge status without triggering side effects. Recovery does not create challenges directly; the caller supplies a challenge ID that was already verified.

### Storage

Recovery flows live in `recovery.recovery_flow` (V5 migration). Check constraints enforce valid statuses and transitions. The table records `risk_score`, `classification`, `identity_challenge_id`, `eligible_after`, and timestamps.

### API

```
POST /api/v1/recovery                        — initiate
POST /api/v1/recovery/{id}/confirm-identity   — attach verified challenge
POST /api/v1/recovery/{id}/complete           — attempt completion (checks eligibility)
POST /api/v1/recovery/{id}/review             — operator approve/reject
```

Responses are uniform and do not reveal whether the account exists. All error responses use RFC 7807 problem details.

## Consequences

### Positive

- explicit state transitions prevent skipping identity verification;
- risk-based delays give defenders time to respond to high-risk attempts;
- manual review provides a human-in-the-loop checkpoint for the highest risk tier;
- the challenge module boundary is respected — recovery reads status, never mutates challenge state;
- uniform error responses resist enumeration.

### Negative

- the `DELAYED` state requires a background process or retry to transition to `COMPLETED` or `MANUAL_REVIEW` (not yet implemented);
- classification thresholds are hardcoded; per-event-type policies are deferred;
- no per-account recovery cooldown yet.

## Guardrails

- recovery risk score is bounded 0–100;
- `eligible_after` is computed from a UTC clock injected into the service;
- transition violations throw `IllegalRecoveryTransitionException` (409 Conflict);
- the module owns its persistence; no other module reads or writes the `recovery` schema.

## Revisit criteria

This decision may be revisited when:

- a scheduler or event-driven process is needed to advance `DELAYED` flows;
- per-event-type or per-account recovery policies are introduced;
- recovery cooldowns or velocity controls are needed;
- real notification providers are integrated for recovery alerts.
