# ADR 0006: Deterministic replay and shadow policy evaluation

- Status: Accepted
- Date: 2026-07-22

## Context

Safe policy rollout requires confidence that a new policy version will not change outcomes in unexpected ways. Two capabilities support this:

1. **Replay** — re-evaluate a historical decision using the same inputs and algorithm version to verify that the recorded outcome is reproducible.
2. **Shadow evaluation** — evaluate a candidate policy version against live risk inputs and compare the outcome to the currently active policy, without affecting the live decision.

Both operations must be side-effect-free: no challenges, no recovery flows, no notifications, no state mutations.

The `audit` module stores decision traces with normalized context, risk score, algorithm version, and policy version. The `policy` module can evaluate any persisted policy version by key and version number. A new module is needed to orchestrate these capabilities without coupling them to the live decision path.

## Decision

Introduce a `simulation` module that depends on `audit` (read-only) and `policy` (version-specific evaluation). The module exposes two operations via `SimulationService`.

### Replay

Loads a decision trace from the audit store by ID, re-evaluates the policy at the recorded version using the recorded risk score, and compares the re-evaluated outcome to the original.

- **Input**: protection request ID (UUID).
- **Output**: `ReplayResult` with original outcome, re-evaluated outcome, and a `matches` boolean.
- If the trace is not found, returns an error — replay does not fabricate results.

### Shadow evaluation

Evaluates both the currently active policy and a candidate policy version for the same policy key and risk score, then compares the outcomes.

- **Input**: policy key, candidate version, risk score.
- **Output**: `ShadowEvaluationResult` with live outcome, candidate outcome, a `diverged` boolean, and a `PolicyComparisonSummary`.

### Side-effect safety

Both operations use `@Transactional(readOnly = true)`. Neither creates, updates, or deletes any rows. The `PolicyEvaluationService.evaluateVersion()` method is read-only and does not transition policy status or publish events.

### New public APIs in other modules

- `audit.DecisionTraceQuery` — read-only query interface for loading decision traces by protection request ID. Implementation (`JdbcDecisionTraceQuery`) uses raw JDBC to avoid JPA first-level cache interference.
- `audit.DecisionTraceView` — immutable projection record with all fields needed for replay.
- `policy.PolicyEvaluationService.evaluateVersion(policyKey, policyVersion, riskScore)` — evaluates any persisted policy version.

### Dependency direction

```
simulation -> audit   (DecisionTraceQuery)
simulation -> policy  (PolicyEvaluationService)
```

The simulation module has no dependencies on `protection`, `challenge`, or `recovery`. It cannot trigger side effects because it has no access to mutation APIs.

### API

```
GET  /api/v1/simulation/replay/{protectionRequestId}  — replay a historical decision
POST /api/v1/simulation/shadow                         — shadow-evaluate a candidate policy
```

## Consequences

### Positive

- replay validates determinism without re-running the full decision pipeline;
- shadow evaluation enables safe policy comparison before activation;
- no new mutable state or persistence schema required;
- the module is structurally incapable of side effects due to its dependency choices.

### Negative

- replay can only verify policies that are still persisted; deleted or expired versions are not covered;
- shadow evaluation compares outcomes at a single risk score, not across the full distribution;
- no batch replay or statistical aggregation yet.

## Guardrails

- both operations are wrapped in read-only transactions;
- the simulation module has no write access to any schema;
- `ApplicationModules.verify()` enforces that simulation depends only on `audit` and `policy`.

## Revisit criteria

This decision may be revisited when:

- batch replay across all decisions for a time window is needed;
- statistical impact summaries (false-positive/negative rates) are required;
- A/B or canary policy deployment is introduced through an accepted issue and ADR.
