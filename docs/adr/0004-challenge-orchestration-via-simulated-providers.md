# ADR 0004: Challenge orchestration via simulated providers

- Status: Accepted
- Date: 2026-07-21
- Updated: 2026-07-24

## Context

When AccountShield requires stronger proof, the platform must orchestrate a challenge without allowing that proof to authorize a different operation. A challenge verified for login step-up must not authorize recovery, credential change, policy activation, or another resource.

The project does not integrate real SMS, e-mail, or WebAuthn providers in the first releases. Providers remain simulated locally so the implementation can focus on orchestration, state-machine integrity, binding, concurrency, and retry safety.

## Decision

The `challenge` module owns creation, verification, expiration, retry limits, binding, and one-time consumption. Every challenge is bound at creation to:

- an opaque account or subject reference;
- a `ChallengePurpose`;
- a purpose-specific `contextId`;
- a challenge type.

Initial purposes are:

- `PROTECTION_STEP_UP`;
- `RECOVERY_IDENTITY`;
- `PRIVILEGED_OPERATION`.

The context is chosen by the creating use case. Protection step-up uses the protection request ID. Recovery identity uses the recovery flow ID. Privileged operations derive a resource-and-action-specific `contextId` (recovery review uses the recovery flow ID directly; policy activation/retirement hash `"policy:" + action + ":" + policyKey + ":" + version` into a deterministic name-based UUID via `UUID.nameUUIDFromBytes`, keeping the action in the binding so a challenge issued for retiring a version cannot be spent activating it instead). A consumer must present the exact purpose, context, and account binding.

### Privileged operations (first real caller)

`PRIVILEGED_OPERATION` existed in the enum from the start but had no caller until policy activation, policy retirement, and manual recovery review each added a `.../step-up` endpoint: it issues a `PRIVILEGED_OPERATION` challenge for the caller (identity from the authenticated principal, never the request body) bound to that specific resource and action. The caller verifies it through the existing shared `POST /api/v1/challenges/{id}/verify`, then presents the resulting `challengeId` back to the privileged endpoint itself, which consumes it (`ChallengeService.consume`) before performing the action — inside the same transaction, so a rejected step-up leaves the resource state untouched. No new persistence or concurrency mechanism was needed: this purpose already had the same one-time consumption, purpose/context/account binding, and `@Version`-backed exactly-one-winner guarantee as `RECOVERY_IDENTITY`. Every attempt — successful or rejected — publishes a module-owned audit event (`PrivilegedPolicyActionAttempted`, `PrivilegedRecoveryActionAttempted`) logged by the existing `SecurityEventLogger`.

Dead-letter replay, evidence-bundle export, and canary rollout changes have no implemented endpoints yet; when they exist, they should consume a `PRIVILEGED_OPERATION` challenge the same way rather than inventing a parallel mechanism.

### State machine

```text
CHALLENGED -> VERIFIED   correct proof within retry budget
CHALLENGED -> FAILED     retry budget exhausted
CHALLENGED -> EXPIRED    TTL exceeded
VERIFIED   -> CONSUMED   authorized use succeeds exactly once
```

`VERIFIED` means that the proof was accepted but has not yet authorized an operation. `CONSUMED`, `FAILED`, and `EXPIRED` are terminal. A consumed challenge cannot be verified or consumed again.

### Verification and consumption

Verification and authorization are intentionally separate:

1. `verify` validates proof material and the purpose/context binding;
2. `consume` validates purpose, context, account, status, and expiry;
3. consumption atomically changes `VERIFIED` to `CONSUMED` and records `consumed_at`.

The persistence entity uses optimistic locking. Concurrent consumers may race, but only one transaction can commit the state transition. Losing consumers receive a generic rejection that does not reveal whether the challenge is absent, mismatched, or already consumed.

### Retry budget and TTL

- Each plan has three verification attempts.
- Each plan has a ten-minute TTL.
- Wrong proofs decrement the remaining-attempt counter.
- Exhaustion changes the state to `FAILED`.
- Expiry changes the state to `EXPIRED` when verification or consumption observes it.
- Binding mismatch does not consume an attempt.

### Simulated providers

The local adapters support:

- `TOTP_SIMULATED`;
- `EMAIL_SIMULATED`;
- `WEBAUTHN_SIMULATED`.

No external calls are made. Simulated proof material is never logged.

Each type has a distinct simulated proof codec (`ChallengeCodecRegistry`, keyed by `ChallengeType`): `TOTP_SIMULATED` and `EMAIL_SIMULATED` issue a `SecureRandom`-backed six-digit numeric code; `WEBAUTHN_SIMULATED` issues a `SecureRandom`-backed 32-character opaque assertion token, not a six-digit code, since a real WebAuthn assertion is not code-shaped.

Proof material is knowable only at the moment of issuance. `create()` publishes a `ChallengeIssued` domain event (`challengeId`, `accountReference`, `challengeType`, `purpose`, `contextId`, `issuedCode`, `expiresAt`) carrying the raw value once — the same "publish at creation, a future real-provider adapter subscribes and delivers it" pattern already used for `RecoveryAuthorizationIssued`. Nothing downstream of that event, including `ChallengePlan` and the HTTP layer, can read the value back.

### Integration

The `protection` module creates `PROTECTION_STEP_UP` challenges bound to the protection request ID.

The `recovery` module creates `RECOVERY_IDENTITY` challenges bound to the recovery flow ID. After the proof has been verified, recovery must consume that exact challenge before advancing identity confirmation.

Other modules interact only through the challenge module's public commands:

- `CreateChallengeCommand`;
- `ChallengeVerificationCommand`;
- `ConsumeChallengeCommand`.

### Storage

`challenge.challenge_plan` persists:

- account reference;
- challenge type;
- purpose;
- context ID;
- status;
- retry counters;
- a keyed HMAC-SHA256 digest of the proof material (`code_hash`), never the raw value, compared with `MessageDigest.isEqual` for a constant-time check; cleared once a challenge reaches `VERIFIED`, `FAILED`, or `EXPIRED`, since nothing needs to compare against it again;
- creation and expiry timestamps;
- consumption timestamp;
- optimistic-lock version.

Database constraints enforce supported states and purposes, valid retry ranges, expiry ordering, and the invariant that `consumed_at` exists only for `CONSUMED` records.

Historical rows created before explicit binding are migrated with their own challenge ID as context. This fail-safe migration prevents old demo challenges from authorizing current operations.

### HTTP verification API

`POST /api/v1/challenges/{id}/verify` requires:

```json
{
  "providedCode": "123456",
  "purpose": "PROTECTION_STEP_UP",
  "contextId": "f47ac10b-58cc-4372-a567-0e02b2c3d479"
}
```

Purpose or context mismatch returns the generic problem code `CHALLENGE_USE_REJECTED`. Failed and expired state transitions keep their stable invalid-state responses.

Consumption is an internal application operation. It is not exposed as a generic public endpoint because only the purpose-owning use case may authorize the resulting business action.

## Consequences

### Positive

- proof cannot be reused across operations or resources;
- a successful challenge authorizes at most one operation;
- concurrent consumption has exactly one winner;
- verification retries remain idempotent until consumption;
- generic rejection reduces enumeration and state-disclosure risk;
- purpose and context are available for audit and investigation.

### Negative

- callers must propagate purpose and context explicitly;
- verified challenges require a separate consumption step;
- optimistic-lock conflicts must be translated into domain-level rejection;
- existing API clients must include the new binding fields.

## Guardrails

- no module reads or writes the challenge schema directly;
- purpose, context, and account binding are checked before status details are exposed;
- consumed challenges cannot return to another state;
- proof material is never logged, persisted, or included in outbox/integration events — it exists only in the synchronous `ChallengeIssued` in-process application event published at creation, never serialized outside the JVM;
- tests cover mismatches, retries, expiry, reuse, and concurrent consumption.

## Revisit criteria

Revisit this ADR when:

- real provider adapters are introduced (they would subscribe to `ChallengeIssued` instead of the caller discarding it);
- configurable retry and TTL policies are introduced;
- a privileged-operation purpose is split into more granular purpose values.
