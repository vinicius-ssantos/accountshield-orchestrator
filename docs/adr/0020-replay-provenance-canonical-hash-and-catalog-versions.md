# ADR 0020: Replay compares a canonical input hash and validates catalog/engine versions

- Status: Accepted
- Date: 2026-07-26

## Context

ADR 0019 (#21) closed the gap where replay never re-ran the historical risk algorithm, but left named remainder items to #43 explicitly (see ADR 0019 "Revisit criteria"): normalized-input schema version, reason-catalog version, decision-engine version, application commit SHA, canonical input hash, and recovery-classification comparison.

Direct code inspection found:

- `signalSchemaVersion` was already persisted in `normalized_context` (since #45 / ADR 0013) and already read during envelope reconstruction, but never surfaced on `ReplayResult` or compared against anything.
- A canonical-input hash already existed as `protection.internal.ProtectionDecisionApplicationService.fingerprint(command)` — a **private** method computing a SHA-256 over clientId/accountReference/eventType/signals — already persisted as `protection_request.request_fingerprint` and exposed on `DecisionTraceView.requestFingerprint()`, but replay never recomputed it from the reconstructed input and compared.
- No "reason-catalog version" or "decision-engine version" concept existed anywhere.
- `recovery.internal.RecoveryClassificationRule` is a real, already-versioned (`recovery-classification-1.0`), package-private concern, but it is a *downstream* consequence of `RecoveryAuthorizationIssued`, correlated only via a separately-created `recovery_flow` row that may not exist for every `START_RECOVERY` decision (a user may never actually initiate recovery).
- No git-commit-SHA capture exists in the build (no `git-commit-id-plugin` or equivalent in `pom.xml`).

## Decision

### Canonical input hash: extract the existing algorithm, don't duplicate it

`protection.RequestFingerprint` (new public utility, not `.internal`) holds the exact byte-layout/SHA-256 logic that was private in `ProtectionDecisionApplicationService.fingerprint()`. That method now delegates to it — same output, verified behavior-preserving by the existing fingerprint-dependent tests continuing to pass unchanged. `simulation.internal.SimulationApplicationService.replay()` calls the same utility over the *reconstructed* signals and compares the result against `trace.requestFingerprint()`, adding a `"canonicalInputHash: ..."` entry to `mismatches` on divergence. This is a genuine new correctness check, independent of score recomputation: it proves the reconstructed input is byte-for-byte the same canonical input the original decision hashed.

New module edge `simulation -> protection` (public API only). Verified safe via Spring Modulith's automatic module verification: no cycle (`protection` does not depend on `simulation`), consistent with the existing `recovery -> protection` edge for `RecoveryAuthorizationIssued`.

### Reason-catalog version: a real validity check, not just a label

`risk.RiskReasonCatalog` declares `CURRENT_VERSION = "risk-reason-catalog-1.0"` and `KNOWN_CODES` — the exact literal codes `DeterministicRiskAssessmentService` emits today (`COMPROMISED_CREDENTIAL`, `IMPOSSIBLE_TRAVEL`, `FAILED_ATTEMPTS`, `NETWORK_RISK_LOW`, `NETWORK_RISK_MEDIUM`, `NETWORK_RISK_HIGH`, `NEW_DEVICE`, `LOW_CONFIDENCE_SIGNAL`). `ProtectionDecisionApplicationService.normalizedContext()` records `"reasonCatalogVersion"` (same JSONB-extension pattern used by every prior addition to this map — clientId in #26, schema/provider/confidence in #45). During replay, every code in the *original* trace's reasons is checked against `KNOWN_CODES`; an unrecognized code adds a `"reasonCatalogVersion: ..."` mismatch. This makes "historical fixtures remain reproducible, and unavailable/renamed reason codes fail explicitly" concrete: a trace referencing a since-retired code is flagged, not silently accepted as a match.

### Decision-engine version: a marker, deliberately not a registry

`protection.DecisionEngineVersion.CURRENT = "decision-engine-1.0"` mirrors the `RiskSignalEnvelope.CURRENT_SCHEMA_VERSION` naming convention. Recorded in `normalizedContext()` and surfaced on `ReplayResult`. Unlike `RiskAssessmentService` (ADR 0019), this is **not** given a registry: there is exactly one orchestration implementation (`ProtectionDecisionApplicationService`), so a registry indexed by version would be ceremony around a single entry. The constant exists so a future incompatible orchestration change has something to compare against and bump.

### `ReplayResult` gains three fields, no new dependency on recovery

`normalizedInputSchemaVersion` (surfaced, not compared — there has only ever been one schema shape, so there is nothing to diverge from yet), `reasonCatalogVersion`, `decisionEngineVersion`. Recovery-classification comparison is explicitly deferred (see below).

### Historical-fixture reproducibility

`SimulationApplicationServiceTest` gained full-history fixture tests (a "recovery request" framed decision and a plain one) asserting a full match with zero mismatches across all three new provenance fields, plus dedicated tests for canonical-hash mismatch and unknown reason-catalog code. `ProtectionDecisionIntegrationTest`/`SimulationIntegrationTest` assert the new context keys and `ReplayResult` fields end-to-end against real Postgres.

## Alternatives considered

- **A separate hashing utility for replay**, independent from `ProtectionDecisionApplicationService.fingerprint()` — rejected: two implementations of "the canonical hash" can drift; extracting the one true implementation to a shared public type is what makes the comparison meaningful at all.
- **Giving `DecisionEngineVersion` a registry like `RiskAlgorithmRegistry`** — rejected as premature ceremony; revisit only if a second concrete orchestration implementation actually appears.
- **Folding recovery-classification comparison into this issue** — rejected. `RecoveryClassificationRule` is real and already versioned, but comparing it during replay requires: promoting it to public, a new read-only query port in `recovery` (mirroring `audit.DecisionTraceQuery`), a new `simulation -> recovery` module edge, and explicit handling of "no recovery flow exists yet for this decision" as a non-error state (a `START_RECOVERY` decision does not guarantee the user ever initiated recovery). That is a coherent, self-contained piece of work deserving its own issue.
- **Application commit SHA** — rejected for this slice: requires build-tooling changes (`git-commit-id-plugin` or equivalent) that cannot be verified in this environment (no local Maven), and is purely informational metadata with no comparison/correctness role, unlike every other item here.

## Consequences

### Positive

- replay now proves the reconstructed input is byte-identical to what was originally hashed, not just that it produces the same score;
- a retired or renamed reason code in historical data is caught explicitly instead of silently "matching";
- `ProtectionDecisionApplicationService.fingerprint()` has a single, reusable, independently-tested implementation instead of a private duplicate.

### Negative

- `DecisionEngineVersion`/`normalizedInputSchemaVersion` are informational only for now — no live "known engine versions" set exists to validate against, since there has only ever been one engine version. Accepted as proportionate; a registry can be introduced if/when a second version actually ships.

## Guardrails

- `simulation` depends only on `protection`'s public API (`RequestFingerprint`, `ClientId`, `DecisionEngineVersion`) and `risk`'s public API (`RiskReasonCatalog`) — no new dependency on `recovery` was introduced;
- `RequestFingerprint.compute(...)` is the single implementation used by both the original decision path and replay — verified by `RequestFingerprintTest` and the unchanged fingerprint assertions in `ProtectionDecisionApplicationServiceTest`;
- an unrecognized reason code always produces a mismatch — verified by `SimulationApplicationServiceTest.replayDetectsUnknownReasonCatalogCode`.

## Revisit criteria

This decision should be revisited when:

- recovery-classification comparison is picked up as its own issue and needs a `simulation -> recovery` read path;
- application commit SHA capture becomes feasible to verify end-to-end via CI;
- a second decision-engine version actually ships, to decide whether `DecisionEngineVersion` needs a registry.

## Links

- Issue #43
- [ADR 0019](0019-deterministic-replay-algorithm-registry.md) (the replay comparison machinery this extends), [ADR 0013](0013-risk-signal-provenance-envelope.md) (origin of `signalSchemaVersion`), [ADR 0003](0003-idempotency-via-caller-key-and-fingerprint.md) (origin of the request-fingerprint concept)
- Tests: `RequestFingerprintTest`, `RiskReasonCatalogTest`, `SimulationApplicationServiceTest`, `SimulationControllerTest`, `SimulationIntegrationTest`, `ProtectionDecisionApplicationServiceTest`, `ProtectionDecisionIntegrationTest`
