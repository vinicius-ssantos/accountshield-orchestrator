# ADR 0013: Risk signal provenance envelope with staleness and confidence handling

- Status: Accepted
- Date: 2026-07-24

## Context

Issue #45 asked for risk signals to carry provenance, freshness, and confidence so that a decision is explainable and not silently based on stale or low-trust input. Before this change, `RiskSignals` (`risk/RiskSignals.java`) was a bare value object — no provider, no observation timestamp, no confidence, no schema version, no marker distinguishing simulated input from a real provider. `ProtectionDecisionApplicationService.decide()` fed it directly into `RiskAssessmentService.assess()` and snapshotted its raw fields into `audit.decision_trace.normalized_context`.

There is no internal "risk signal provider" abstraction today — API callers supply signal values directly over HTTP — and no operator-facing endpoint exposes decision-trace detail. This introduces a versioned data contract (`schemaVersion`) and a new deterministic rejection behavior at the decision boundary (stale signals refuse to produce a decision at all), which — per `docs/adr/README.md`'s trigger list ("establishes a long-lived public API/event versioning policy", "accepts a significant operational trade-off") — warrants a record here, following the same reasoning applied to #32/ADR 0012.

## Decision

### `RiskSignalEnvelope`

A new record (`risk/RiskSignalEnvelope.java`) wraps the existing, unchanged `RiskSignals` with `provider` (String), `observedAt` (Instant), `confidence` (`SignalConfidence`: HIGH/MEDIUM/LOW), `schemaVersion` (defaults to `risk-signal-envelope-1.0`), and `simulated` (boolean). `RiskSignals` itself is untouched — the envelope is purely additive metadata, keeping the existing deterministic scoring rules unchanged.

### Staleness: reject, don't guess

`ProtectionDecisionApplicationService.decide()` checks `envelope.isStale(now, maxSignalAge)` (default 5 minutes, `accountshield.risk.max-signal-age`) before the rate limiter, idempotency, or any persistence runs. A stale envelope throws `StaleRiskSignalException`, mapped to `422 Unprocessable Entity` (`STALE_RISK_SIGNAL`). No decision is produced, and no fallback scoring is attempted — the simplest deterministic behavior, with nothing hidden for an operator to reverse-engineer.

### Confidence: one more deterministic reason

`RiskAssessmentService.assess(RiskSignalEnvelope)` unwraps `envelope.signals()` for the existing five scoring rules unchanged, then appends a sixth: `LOW_CONFIDENCE_SIGNAL` contributes 10 points when `confidence == LOW`. This reuses the existing reason-code/contribution mechanism (`RiskAssessment.reasons()`), so confidence handling is visible in every decision trace exactly like every other risk factor — no separate branch or hidden adjustment.

### Backward-compatible public API

`ProtectionDecisionRequest` gains optional `signalProvider`, `signalObservedAt`, `signalConfidence` fields. Omitting them defaults to `"CLIENT_SUPPLIED"`, "now", and `HIGH` respectively — an existing caller who sends none of them gets a fresh, high-confidence envelope and sees no behavior change. `simulated` is not caller-settable; it is always `true` server-side, since no real risk-signal provider exists yet (mirrors the `SimulationModeGuard` precedent from #38).

### Provenance in the audit trail

`normalizedContext()` (already the established home for contextual metadata, e.g. the `recoveryRequest` flag) gains `signalProvider`, `signalObservedAt`, `signalConfidence`, `signalSchemaVersion`, `signalSimulated`. No migration needed — reuses the existing `audit.decision_trace.normalized_context` JSONB column, and is exactly what makes provenance available through `DecisionTraceView` to `simulation`/replay (per ADR 0006, replay re-evaluates policy against the historical risk score, not raw signals — provenance is for audit/inspection, not recomputation).

## Alternatives considered

- **Per-signal provenance** (a provider/timestamp for each individual field, not one envelope per request) — rejected as disproportionate; no real multi-provider integration exists yet to justify the complexity. Revisit if one is introduced.
- **Silently treating stale/low-confidence signals as worst-case** — rejected; an invented fallback score is harder to explain and audit than an explicit rejection, and contradicts this platform's explainability requirement (`CLAUDE.md`).
- **Changing `RiskSignals` itself to carry provenance fields** — rejected; would touch every existing signal-scoring code path for no benefit, when the metadata is naturally a wrapper concern.

## Consequences

### Positive

- a decision can never silently use a stale signal;
- confidence is explainable through the same mechanism as every other risk factor;
- provenance is captured in the append-only audit trail without a schema migration;
- the public API stays backward-compatible for callers that don't yet send provenance.

### Negative

- `RiskAssessmentService.assess()`'s signature change ripples through every test that constructs signals directly (mechanical, but a wide diff);
- a legitimate caller with clock skew beyond `max-signal-age` will be rejected until it corrects `signalObservedAt` or omits it (defaulting to server time);
- "provider" is presently just caller-declared metadata, not a verified/pluggable adapter — a malicious or buggy caller can claim any provider name.

## Guardrails

- staleness is checked before any side effect (rate limiter, idempotency, persistence) — `ProtectionDecisionApplicationServiceTest.staleSignalEnvelopeIsRejectedBeforeAnySideEffect` and `ProtectionDecisionIntegrationTest.staleSignalEnvelopeProducesNoProtectionRequestRow` assert zero rows/interactions;
- `RiskAssessment`'s existing invariant (reason contributions must sum to the score) still holds with the new `LOW_CONFIDENCE_SIGNAL` reason, reusing the same capping helper as every other factor;
- `simulated` cannot be set to `false` by any caller-supplied input today.

## Migration/compatibility implications

No schema migration. `ProtectionDecisionRequest`'s new fields are optional with safe defaults, so existing integrations are unaffected. `RiskAssessmentService.assess()`'s signature change is source-incompatible for any external implementer of the interface, but it has exactly one production implementation (`DeterministicRiskAssessmentService`) in this codebase.

## Revisit criteria

This decision should be revisited when:

- a real, pluggable risk-signal provider is introduced (would need per-provider contract tests and possibly per-field provenance);
- an operator-facing API needs to expose decision-trace provenance (would need explicit redaction rules for that surface);
- `max-signal-age` needs to vary by event type or policy rather than being a single global default.

## Links

- Issue #45
- [ADR 0006](0006-deterministic-replay-and-shadow-evaluation.md) (replay uses the historical risk score, not raw signals — this ADR's provenance capture is for audit, not recomputation)
- [ADR 0012](0012-pseudonymous-subject-tokens-for-integration-events.md) (same reasoning pattern for when a new ADR is warranted)
- Tests: `RiskSignalEnvelopeTest`, `DeterministicRiskAssessmentServiceTest`, `ProtectionDecisionApplicationServiceTest`, `ProtectionDecisionIntegrationTest`, `ProtectionDecisionControllerTest`
