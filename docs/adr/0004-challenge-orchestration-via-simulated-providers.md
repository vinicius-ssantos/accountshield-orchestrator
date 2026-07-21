# ADR 0004: Challenge orchestration via simulated providers

- Status: Accepted
- Date: 2026-07-21

## Context

When a protection decision results in `REQUIRE_STEP_UP`, the platform must orchestrate a challenge flow. The challenge module owns the lifecycle of challenge plans: creation, verification attempts, expiration, and terminal states.

The project must not integrate real SMS, e-mail, or biometric providers in the first releases. All providers are simulated locally, as stated in the product boundary. This keeps the focus on the orchestration logic, state machine integrity, and retry safety rather than on provider-specific concerns.

## Decision

Introduce a `challenge` module with its own PostgreSQL schema and a deterministic state machine. Challenge plans are created by the `protection` module when a decision requires step-up verification.

### State machine

```
CHALLENGED -> VERIFIED   (correct code within retry budget)
CHALLENGED -> FAILED     (retry budget exhausted)
CHALLENGED -> EXPIRED    (TTL exceeded before or during verification)
```

States are explicit and persisted. A challenge can never transition out of a terminal state (`VERIFIED`, `FAILED`, `EXPIRED`).

### Retry budget and TTL

- Each challenge plan has a fixed maximum of 3 verification attempts.
- Each plan has a 10-minute time-to-live.
- Wrong codes decrement the remaining attempt counter.
- When attempts reach zero, the plan transitions to `FAILED`.
- When the TTL is exceeded, the plan transitions to `EXPIRED` (lazily evaluated during verification).

### Simulated providers

Providers generate a deterministic 6-digit numeric code stored alongside the challenge plan. Three simulated challenge types are supported:

- `TOTP_SIMULATED`
- `EMAIL_SIMULATED`
- `WEBAUTHN_SIMULATED`

No external calls are made. The provider abstraction (`ChallengeProvider`) exists so that real integrations can be added later without changing the orchestration logic.

### Integration with protection

The `protection` module calls the `challenge` module's public API to create a plan when the decision outcome is `REQUIRE_STEP_UP`. The `ProtectionDecisionResult` includes an optional `ChallengePlan` with the challenge ID, type, and expiration time.

### Storage

Challenge plans live in the `challenge.challenge_plan` PostgreSQL table within the `challenge` module. Check constraints enforce:

- `status` must be one of `PENDING`, `CHALLENGED`, `VERIFIED`, `FAILED`, `EXPIRED`;
- `max_attempts` between 1 and 10;
- `remaining_attempts` between 0 and `max_attempts`;
- `expires_at > created_at`.

### API

`POST /api/v1/challenges/{id}/verify` accepts a verification code and returns the result:

- `CHALLENGED` + correct code: `200 OK` with `verified: true`.
- `CHALLENGED` + wrong code: `200 OK` with `verified: false` and decremented `remainingAttempts`.
- `FAILED`: `409 Conflict` with `INVALID_CHALLENGE_STATE`.
- `EXPIRED`: `410 Gone` with `INVALID_CHALLENGE_STATE`.

## Consequences

### Positive

- the state machine prevents indefinite retry and MFA fatigue attacks;
- the retry budget (3 attempts) is enforced at the domain level, not by the provider;
- simulated providers allow full local testing without external dependencies;
- the provider abstraction leaves room for real integrations without architectural change;
- terminal states are immutable, preserving auditability.

### Negative

- challenge codes are stored in the database (acceptable because all providers are simulated and no real credentials are involved);
- the 10-minute TTL and 3-attempt budget are hardcoded in this phase; configuration is deferred;
- no per-account cooldown between challenge plans yet.

## Guardrails

- challenge codes are never logged;
- verification errors return uniform problem details that do not reveal whether the challenge exists;
- the retry budget mitigates brute-force and MFA fatigue scenarios;
- the module owns its persistence; no other module reads or writes the `challenge` schema.

## Revisit criteria

This decision may be revisited when:

- real provider integrations (SMS, e-mail, WebAuthn) are introduced through an accepted issue and ADR;
- a per-account cooldown or velocity control is needed between challenge plans;
- Redis is introduced for ephemeral challenge tracking;
- the retry budget or TTL needs to be configurable per policy or per account.
