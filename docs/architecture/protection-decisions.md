# Explainable protection decisions

## Scope

This slice implements the first complete AccountShield decision path:

```text
POST /api/v1/protection-decisions
-> bounded signal normalization
-> deterministic risk assessment
-> active policy evaluation
-> protection request persistence
-> append-only decision trace
```

It does not implement caller idempotency, challenge creation, recovery, shadow evaluation, replay, Redis, Kafka, or external provider calls.

## Public contract

The endpoint accepts an opaque account reference, a supported event type, and bounded derived signals. Optional signals use safe defaults:

- failed attempts: `0`;
- new device: `false`;
- impossible travel: `false`;
- compromised credential: `false`;
- network risk: `LOW`.

Passwords, authentication tokens, MFA secrets, raw device fingerprints, raw IP addresses, and caller-provided scores or outcomes are forbidden.

Invalid values return RFC 9457 Problem Details with `INVALID_PROTECTION_REQUEST`. If no complete active policy can be resolved, the service fails closed with `503` and `ACTIVE_POLICY_UNAVAILABLE` without exposing internal configuration.

## Deterministic risk algorithm

Algorithm version: `risk-rules-1.0`.

| Signal | Contribution |
| --- | ---: |
| compromised credential | 40 |
| impossible travel | 35 |
| failed attempts | 3 per attempt, capped at 30 |
| medium network risk | 10 |
| high network risk | 20 |
| new device | 15 |

Rules execute in the listed stable order. The total is capped at 100, and the applied ordered contributions always sum exactly to the final score.

Risk bands:

- `LOW`: 0–29;
- `MEDIUM`: 30–69;
- `HIGH`: 70–100.

Identical normalized signals and the same algorithm version produce the same score, band, and ordered reasons.

## Versioned policy

Flyway seeds the immutable active policy `account-protection-default` version `1.0.0`.

| Score | Outcome |
| --- | --- |
| 0–29 | `ALLOW` |
| 30–69 | `REQUIRE_STEP_UP` |
| 70–100 | `TEMPORARILY_BLOCK` |

The policy module resolves only the `ACTIVE` version. Missing or incomplete thresholds are treated as unavailable configuration rather than silently falling back to a permissive outcome.

## Transaction and ownership

The `protection` module owns orchestration and the `protection.protection_request` write. It calls only public contracts from `risk`, `policy`, and `audit`; it does not import their internal repositories or entities.

One PostgreSQL transaction covers:

1. risk and policy evaluation;
2. insertion of the protection request;
3. insertion of the decision trace;
4. insertion of ordered reason contributions.

The audit recorder requires an existing transaction through `Propagation.MANDATORY`. A failure while recording audit data rolls back the already-flushed protection request. Database triggers reject updates and deletes of decision traces and reasons.

## Persisted explainability

Each decision trace stores:

- opaque request and decision identifiers;
- SHA-256 request fingerprint;
- algorithm version;
- policy key and version;
- final outcome and bounded risk score;
- minimized normalized context;
- ordered machine-readable reason contributions;
- UTC decision timestamp.

The fingerprint supports deterministic request identity but is not yet the durable idempotency contract. Reuse and conflict behavior belong to the next dedicated slice.

## Verification

The implementation is covered by:

- unit tests for scoring determinism, caps, contribution ordering, and invalid bounds;
- unit tests for policy thresholds and fail-closed behavior;
- orchestration tests for the persisted command and stable fingerprint;
- web tests for successful responses and RFC 9457 errors;
- Testcontainers tests for all three initial outcomes, persisted versions and reasons;
- a PostgreSQL rollback test that forces an audit failure after flushing the protection request;
- Spring Modulith architecture verification.
