# ADR 0010: Recovery trust boundaries — derive risk score from decision trace and bind challenge to flow

- Status: Accepted
- Date: 2026-07-23

## Context

The recovery module had two trust-boundary flaws that undermined its security model:

1. **Client-supplied risk score.** The `InitiateRecoveryCommand` accepted `riskScore` directly from the caller. An attacker could initiate recovery with `riskScore: 0` and receive `IMMEDIATE` classification, bypassing the risk-based delay and manual-review gates entirely.

2. **Unbound identity challenge.** The `confirmIdentity` method verified the challenge via `challengeService.verifyIdentityForRecovery(challengeId)` but never checked that the supplied `challengeId` matched the `identityChallengeId` stored on the recovery flow. A verified challenge from a different account or a different recovery flow could confirm identity for this flow.

## Decision

### Risk score derived from decision trace

Remove `riskScore` and `accountReference` from `InitiateRecoveryCommand`. The command now accepts only `protectionRequestId` and `eventType`. The `RecoveryApplicationService` looks up the `DecisionTraceView` via `DecisionTraceQuery` (audit module) to obtain the `accountReference` and `riskScore` that AccountShield itself computed.

This creates a new module dependency: `recovery -> audit` (read-only, through the public `DecisionTraceQuery` port).

The `protectionRequestId` is persisted on the `recovery_flow` row (V7 migration) for audit traceability.

### Challenge bound to recovery flow

In `confirmIdentity`, before calling the challenge service, verify that `command.challengeId()` equals the `identityChallengeId` stored on the recovery entity. A mismatch throws `UnauthorizedRecoveryInitiationException` (HTTP 422).

## Consequences

### Positive

- an attacker cannot manipulate the risk score to influence recovery classification;
- identity verification is bound to the specific challenge created for this recovery flow;
- the `protectionRequestId` on the recovery flow provides end-to-end audit traceability from the originating protection decision;
- the `recovery -> audit` dependency uses only the public `DecisionTraceQuery` port, preserving module boundaries.

### Negative

- recovery can no longer be initiated without a prior protection decision in the audit trace;
- the new module dependency must be reflected in the architecture dependency direction and the Spring Modulith verify test;
- if the audit trace is purged or unavailable, recovery initiation fails closed.

## Guardrails

- `UnauthorizedRecoveryInitiationException` returns HTTP 422 with a generic message that does not reveal whether the protection request exists, avoiding enumeration;
- the challenge-binding check runs before any challenge-service call, so a foreign challenge ID never reaches the challenge module;
- the V7 migration adds a nullable column to preserve existing rows.

## Revisit criteria

This decision may be revisited when:

- `START_RECOVERY` is added to `ProtectionOutcome` and the policy engine, allowing recovery initiation to be restricted to decisions that explicitly authorize it;
- the recovery flow needs to support scenarios where no prior protection decision exists (e.g. out-of-band recovery requests from an operator).
