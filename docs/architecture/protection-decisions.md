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

Every request carries a risk signal envelope (`risk.RiskSignalEnvelope`, ADR 0013) with provider, observation time, and confidence, in addition to the signal values above. Optional envelope fields default to `signalProvider: "CLIENT_SUPPLIED"`, `signalObservedAt: now`, `signalConfidence: HIGH` when omitted, so existing callers are unaffected. The envelope is always server-marked `simulated: true` — no real risk-signal provider exists yet.

Invalid values return RFC 9457 Problem Details with `INVALID_PROTECTION_REQUEST`. If no complete active policy can be resolved, the service fails closed with `503` and `ACTIVE_POLICY_UNAVAILABLE` without exposing internal configuration. A signal envelope older than `accountshield.risk.max-signal-age` (default 5 minutes) is rejected with `422` and `STALE_RISK_SIGNAL` before any decision is produced — a stale signal never silently behaves as fresh.

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
| low-confidence signal envelope | 10 |

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

## Degradation strategy

Every critical-dependency failure has an explicit, classified strategy (`protection.DegradationStrategy`/`DegradationReason`, ADR 0014) rather than an ad hoc exception. No dependency failure can accidentally produce `ALLOW`.

| Dependency | Reason code | Strategy | Produces a decision? | Retryable? |
| --- | --- | --- | --- | --- |
| Active policy resolution | `ACTIVE_POLICY_UNAVAILABLE` | `FAIL_CLOSED` | No — `503`/`ACTIVE_POLICY_UNAVAILABLE` | Yes, once a complete active policy is available |
| Risk-signal freshness | `RISK_SIGNAL_STALE` | `REJECT_UNAVAILABLE` | No — `422`/`STALE_RISK_SIGNAL` | Yes, with a fresh `signalObservedAt` |
| Challenge provider | `CHALLENGE_PROVIDER_UNAVAILABLE` | `FAIL_CLOSED` | Yes — downgraded to `TEMPORARILY_BLOCK`, `degraded=true` | Yes, once the provider recovers |
| Audit persistence | *(none — transactional rollback)* | `FAIL_CLOSED` | No — whole transaction rolls back | Yes, transient |
| Outbox recording | *(none — transactional rollback)* | `FAIL_CLOSED` | No — whole transaction rolls back | Yes, transient |
| Database connectivity | *(none — generic error response)* | `FAIL_CLOSED` | No — generic non-enumerable `5xx` | Yes, transient |

Audit persistence and outbox recording share the protection-decision transaction (`Propagation.MANDATORY`); a failure in either rolls back the entire decision, so nothing partial is ever committed and there is no decision row to mark as degraded. Database connectivity failures are not given bespoke handling — Spring Boot's default error response is already generic and non-enumerable.

A degraded decision is recorded in `normalized_context` (`degraded`, `degradationReason`), returned to the caller (`ProtectionDecisionResponse.degraded`/`degradationReason`), published in `ProtectionDecisionMade` (so it flows into the outbox integration event and the `accountshield.security` structured log), and counted by the `accountshield.protection.degraded_decisions` Micrometer counter, tagged `reason`.

## Verification

The implementation is covered by:

- unit tests for scoring determinism, caps, contribution ordering, and invalid bounds;
- unit tests for policy thresholds and fail-closed behavior;
- orchestration tests for the persisted command and stable fingerprint;
- web tests for successful responses and RFC 9457 errors;
- Testcontainers tests for all three initial outcomes, persisted versions and reasons;
- a PostgreSQL rollback test that forces an audit failure after flushing the protection request;
- Spring Modulith architecture verification.
