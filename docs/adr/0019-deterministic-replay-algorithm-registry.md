# ADR 0019: Deterministic replay re-runs the algorithm via a versioned registry

- Status: Accepted
- Date: 2026-07-25

## Context

Issue #21 named a precise gap, confirmed by direct code inspection of `simulation.internal.SimulationApplicationService.replay()`: it reloaded the *stored* `trace.riskScore()` and reapplied policy at the recorded version, but never called `RiskAssessmentService`, never reconstructed `RiskSignals`, and never compared bands or reasons. `docs/adr/0006-deterministic-replay-and-shadow-evaluation.md` documented this as the accepted starting point ("re-evaluates the policy at the recorded version using the recorded risk score"). The method also contained a literal duplicated condition:

```java
if (replayed.outcome().name().equals(trace.outcome())
        && replayed.outcome().name().equals(trace.outcome())) {
```

No `RiskAlgorithmRegistry` existed anywhere; `risk-rules-1.0` was a private constant on the single `DeterministicRiskAssessmentService` implementation.

## Decision

### A self-registering `RiskAlgorithmRegistry`, not a wrapper around the one implementation

`RiskAssessmentService` gains `String algorithmVersion()`. `risk.internal.InMemoryRiskAlgorithmRegistry` is constructed with `List<RiskAssessmentService>` (Spring injects every bean implementing the interface) and indexes them by their own declared version, failing fast at startup on any duplicate. Adding a second algorithm version in the future is exactly one new `@Service` bean — zero registry-code changes. `risk.UnknownAlgorithmVersionException` (mirrors `policy.ActivePolicyUnavailableException`) is thrown by `resolve()` for an unregistered version, mapped in a new `simulation.internal.web.SimulationProblemHandler` to `422`/`UNKNOWN_ALGORITHM_VERSION` — the existing `422` pattern already used for `StaleRiskSignalException` for "well-formed request referencing historical state that can't be processed."

### `simulation` gains a real dependency on `risk`'s public API

Confirmed via Spring Modulith's automatic module verification (`ArchitectureTest`) and `risk`'s unrestricted `package-info.java` that `simulation -> risk` is safe to add: no cycle (`risk` depends on nothing else), consistent with the already-existing `policy -> risk` edge.

### Replay reconstructs the exact historical envelope and re-runs the algorithm

`SimulationApplicationService.replay()` now: resolves the recorded `trace.algorithmVersion()` via the registry; reconstructs a `RiskSignalEnvelope` from `DecisionTraceView.normalizedContext()` (the five `RiskSignals` fields, always present since before #45); calls `algorithm.assess(reconstructed)` to get a **freshly recomputed** `RiskAssessment`; and — the core correctness fix — evaluates policy using `recomputed.score()`, not the stored score. Previously, policy was always reapplied against the stored score, so replay never actually validated the algorithm reproduces history, only that policy application is idempotent given a fixed score. This is exactly what "does not re-run the historical risk algorithm" named.

**Legacy-trace defaulting.** Envelope provenance fields added by #45 (`signalProvider`/`signalObservedAt`/`signalConfidence`/`signalSchemaVersion`/`signalSimulated`) are read with `containsKey` guards and defaulted exactly the way the original request-parsing layer (`ProtectionDecisionRequest`) defaults them when absent — because traces recorded *before* #45 shipped don't have these keys, and "identical historical input produces a full match" must hold for genuinely historical data, not only decisions made after this PR. Of these, only `confidence` affects the recomputed score (`LOW_CONFIDENCE_SIGNAL`); the rest exist solely to satisfy `RiskSignalEnvelope`'s non-null constructor validation.

### Field-level mismatch reporting replaces a boolean plus the duplicated condition

`ReplayResult` now carries `originalRiskBand`/`replayedRiskBand`, `originalReasons`/`replayedReasons` (reusing `risk.RiskReason` for both sides), `algorithmVersion`, and `List<String> mismatches` — one entry per differing field (score, band, ordered reasons via `List.equals()`, outcome), with `matches = mismatches.isEmpty()`. The duplicated `&&` condition is gone by construction; it was never given a separate fix because the whole comparison block was rewritten.

### Side-effect-free guarantee, verified against real infrastructure

`replay()` stays `@Transactional(readOnly = true)`, calling only read paths: `decisionTraceQuery` (read), `riskAlgorithmRegistry.resolve(...).assess(...)` (pure computation), `policyEvaluationService.evaluateVersion(...)` (read-only per ADR 0006, over an immutable policy row per ADR 0007). A new Testcontainers integration test asserts row counts in `challenge.challenge_plan`, `recovery.recovery_flow`, `outbox.outbox_event`, and `audit.decision_trace` are unchanged by a replay call.

## Alternatives considered

- **A static `Map<String, RiskAssessmentService>` built by hand in the registry** — rejected in favor of self-registration by declared version; the hand-built map would need editing every time a new algorithm version ships, which is exactly the kind of hidden coupling a "registry" is supposed to remove.
- **Comparing against the live/current risk score at replay time** instead of recomputing via the recorded algorithm version — rejected; it wouldn't validate the *historical* algorithm at all, only whatever is deployed today, defeating the purpose of "evaluate using the exact recorded algorithm version."
- **A separate `HistoricalRiskSignals` type** for reconstruction instead of the existing `RiskSignals`/`RiskSignalEnvelope` — rejected; the existing types already model exactly what's needed, and reusing them means replay is provably calling the *same* `assess()` contract production code calls, not a parallel one that could drift.

## Consequences

### Positive

- replay now genuinely re-runs the historical decision pipeline end to end, closing the gap the issue named;
- a future second algorithm version requires no registry-code changes, only a new bean;
- pre-#45 historical traces still replay correctly via the legacy-defaulting rule;
- field-level mismatches give a caller (or future UI, #72) enough detail to say *what* diverged, not just *that* something did.

### Negative

- reconstructing `RiskSignals` from an untyped `Map<String, Object>` (`((Number) ...).intValue()`, `Boolean.TRUE.equals(...)`) is inherently a little defensive/brittle compared to a typed read — accepted since `normalized_context` is JSONB with no schema enforcement at the DB level, and this mirrors how the map is already read elsewhere in this codebase (`JdbcDecisionTraceQuery`);
- provenance fields beyond algorithm/signals/policy (schema version, reason-catalog version, decision-engine version, commit SHA, canonical input hash, recovery-classification comparison) remain out of scope — explicitly owned by #43.

## Guardrails

- `InMemoryRiskAlgorithmRegistry` fails at construction (Spring context startup) on any duplicate `algorithmVersion()`, never silently overwriting one implementation with another;
- `replay()` never calls anything beyond `DecisionTraceQuery`, `RiskAlgorithmRegistry`/`RiskAssessmentService`, and `PolicyEvaluationService.evaluateVersion` — no challenge, recovery, outbox, or audit-write dependency is reachable from this method, verified by the new side-effect-free integration test;
- policy is always re-evaluated using the recomputed score, never the stored one — verified by `SimulationApplicationServiceTest.replayDetectsOutcomeMismatchDrivenByRecomputedScoreWithoutMutatingHistory`.

## Revisit criteria

This decision should be revisited when:

- #43 lands and needs to extend `ReplayResult`/the comparison further (schema/reason-catalog/decision-engine versions, commit SHA, input hash, recovery-classification);
- a second algorithm version actually ships, to confirm the self-registration design holds up in practice.

## Links

- Issue #21
- [ADR 0006](0006-deterministic-replay-and-shadow-evaluation.md) (the replay/shadow-evaluation module this extends), [ADR 0007](0007-policy-lifecycle-state-machine.md) (immutable policy versions — why "changed policy definition is detected without mutating history" holds by construction)
- Tests: `InMemoryRiskAlgorithmRegistryTest`, `DeterministicRiskAssessmentServiceTest`, `SimulationApplicationServiceTest`, `SimulationControllerTest`, `SimulationIntegrationTest`
