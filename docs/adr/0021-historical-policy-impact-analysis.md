# ADR 0021: Historical policy impact analysis compares a candidate version against recent traces

- Status: Accepted
- Date: 2026-07-26

## Context

`docs/roadmap.md` Gate 4 sequences `#45 → #21+#43 → #46 → #35 → #34`, i.e. issue #35 is meant to build on already-merged deterministic replay (#21/#43, ADR 0019/0020) and the policy analyzer (#46, ADR 0015). `docs/features/README.md` already stated the exact gap: *"Shadow evaluation exists for individual traces; aggregate transition reports and approval gates do not."*

Direct code inspection found:

- `audit.DecisionTraceQuery` had exactly one method, `findByProtectionRequestId(UUID)` — no bulk/paginated read existed anywhere. `JdbcDecisionTraceQuery` hand-writes SQL via `JdbcTemplate`, not a Spring Data repository.
- `simulation.PolicyComparisonSummary` already existed as a record with almost exactly the transition-matrix shape #35 asks for, but it was **completely unused** — no service constructed it, nothing exposed it — and it hand-enumerated only `ALLOW`/`STEP_UP`/`BLOCK` pairs, missing `START_RECOVERY` (added to `ProtectionOutcome` after this record was written). Since nothing referenced it, it was deleted rather than patched.
- `SimulationApplicationService.evaluateShadow` is the existing single-trace precedent for "evaluate a candidate policy version": it calls `PolicyEvaluationService.evaluateVersion(policyKey, candidateVersion, riskScore, context)`, which resolves `(policyKey, version)` **regardless of lifecycle status** (`PolicyVersionRepository.findByPolicyKeyAndVersion`) — a DRAFT, VALIDATED, or APPROVED version can already be evaluated as a "candidate" with zero new policy-module work.
- Each decision's `normalized_context` already records `recoveryRequest` (boolean, since #26) and `protectionEventType`, both needed to correctly re-evaluate a historical trace against a candidate version.
- `outbox.internal.AccountPseudonymizer` (ADR 0012) is the only existing pseudonymization mechanism in the codebase, deliberately scoped package-private to `outbox` at the time.

## Decision

### One new bounded bulk read, not a general query API

`audit.DecisionTraceQuery` gains `findRecentByPolicyKey(String policyKey, int maxSamples)`, implemented as `... WHERE policy_key = ? ORDER BY decided_at DESC LIMIT ?`. Bounded by construction — a caller cannot trigger an unbounded table scan.

### A separate `PolicyImpactAnalysisService`, not folded into `SimulationService`

`simulation.PolicyImpactAnalysisService.analyzeImpact(policyKey, candidatePolicyVersion, maxSamples)` is a new, distinct port from replay/shadow: it operates over a *batch*, has its own configuration (`accountshield.policy.impact.max-divergence-percentage`), and a new dependency on `outbox`'s pseudonymizer. Mixing it into `SimulationService`'s single-trace contract would blur what that interface promises.

`simulation.internal.PolicyImpactAnalysisApplicationService`:
- validates the candidate version exists up front (`evaluateVersion(policyKey, candidatePolicyVersion, 0, standard())`, result discarded) so a policy key with zero historical decisions still fails fast on a genuinely unknown candidate version, rather than silently returning an empty report;
- loads up to `maxSamples` recent traces for `policyKey`;
- for each trace, reconstructs `PolicyEvaluationContext` from `normalizedContext.recoveryRequest` (defaults to standard for legacy traces missing the key) and calls `evaluateVersion` with the trace's **original, already-computed** `riskScore` — it does not reconstruct signal envelopes or re-run `RiskAssessmentService`;
- builds a `Map<String, Map<String, Long>>` transition matrix (original outcome -> candidate outcome -> count) covering all four `ProtectionOutcome` values;
- segments impact in-memory by `protectionEventType` and by `RiskBand.fromScore(riskScore)`;
- collects a bounded list (200) of `DivergentDecision` entries with the account reference redacted via the pseudonymizer;
- computes `divergencePercentage` and `exceedsDivergenceThreshold` against the configured maximum;
- reports the set of original policy versions and algorithm versions actually observed across the sample, rather than assuming a single value.

`@Transactional(readOnly = true)`; no challenge/recovery/outbox/audit-write dependency is reachable, verified by a side-effect-free integration test mirroring the one written for replay (#21).

### Why the original risk score, not a re-run algorithm

This is deliberately a *policy* impact tool, answering "would a different policy have decided differently given what was actually observed?" — not "did the algorithm change?" (that is replay's job, ADR 0019/0020). Re-running the algorithm over a whole historical batch is materially more expensive and conflates two independent questions; a divergence here is attributable to the policy thresholds alone.

### Redaction reuses the one established pseudonymization mechanism

`outbox.internal.AccountPseudonymizer` is promoted to `outbox.AccountPseudonymizer` (public, unchanged implementation and config). This **revises the guardrail stated in ADR 0012** ("`AccountPseudonymizer` is package-private to `outbox/internal`; nothing outside that boundary can call it directly") — the boundary now sits at the `outbox` module, not the `internal` package: the mechanism is intentionally reusable by other modules that need the same account-identifier redaction, rather than each module inventing its own. New module edge `simulation -> outbox`, verified safe: no cycle (`outbox` does not depend on `simulation`), no restrictive `allowedDependencies` on either module's `package-info.java`.

### Approval gates surface a flag; they do not hard-block the governance state machine

The acceptance criterion "candidate approval can be blocked by configured divergence thresholds" is satisfied by `exceedsDivergenceThreshold` on the report — a human approver (maker-checker, ADR 0016) or a future automated gate can act on it. Wiring it as a hard precondition inside `PolicyGovernance.approve()`/`activate()` is deliberately out of scope: it would make an existing, already-tested state transition synchronously run a potentially large historical batch, a real behavioral and performance change to a flow this issue should not silently alter.

## Alternatives considered

- **Extending the existing `PolicyComparisonSummary`** instead of replacing it — rejected; its hand-enumerated ALLOW/STEP_UP/BLOCK fields cannot represent `START_RECOVERY` without becoming a 16-field record, and nothing referenced it, so there was no compatibility cost to redesigning.
- **SQL-side segmentation by client** (a `WHERE normalized_context->>'clientId' = ?` predicate per segment) — rejected for this slice; `clientId` has no dedicated column, and in-memory segmentation over a single bounded fetch is simpler and proportionate to a historical-sample analysis tool.
- **A second, independent pseudonymization utility scoped to `simulation`** — rejected in favor of promoting the one that already exists; two implementations of "redact an account reference" can drift in secret handling or algorithm choice.
- **Hard-blocking policy approval automatically** — rejected as a separate, larger governance-integration change (see above).

## Consequences

### Positive

- a candidate policy version can now be assessed against real historical behavior before activation, closing the exact gap `docs/features/README.md` named;
- the transition matrix and segment breakdowns are computed from a single bounded fetch, not N+1 queries against `decision_trace`;
- account identifiers in the divergent-decision list are redacted using the same, already-audited mechanism as the outbox integration boundary.

### Negative

- per-trace policy evaluation still costs one `evaluateVersion` call per sampled trace (bounded by `maxSamples`, default 5000) — O(n) round-trips against `policy_version`, not batched. Acceptable for a first version; revisit if profiling shows this is too slow for large samples.
- promoting `AccountPseudonymizer` out of `outbox/internal` widens its visibility beyond the boundary ADR 0012 originally drew; any future module reusing it should be a deliberate, reviewed choice, not a default.

## Guardrails

- `findRecentByPolicyKey` always applies a `LIMIT`; there is no code path to an unbounded scan of `audit.decision_trace`;
- `PolicyImpactAnalysisApplicationService` is `@Transactional(readOnly = true)` with no dependency on `challenge`, `recovery`, or `outbox`'s event-recording path — only its pseudonymizer — verified by `PolicyImpactAnalysisIntegrationTest`;
- the divergent-decision list never carries a raw `accountReference` — only the pseudonymized token — verified by `PolicyImpactAnalysisApplicationServiceTest.divergentDecisionsRedactTheAccountReference`.

## Migration/compatibility implications

No schema migration required — `findRecentByPolicyKey` reads existing columns. `PolicyComparisonSummary`'s deletion has no call sites to break (confirmed via repo-wide search).

## Revisit criteria

This decision should be revisited when:

- automated hard-blocking of policy approval based on divergence is picked up as its own governance-integration issue;
- profiling shows the per-trace `evaluateVersion` call pattern is a real bottleneck at the configured `maxSamples` ceiling;
- a second module needs the same account-reference redaction, to confirm `outbox.AccountPseudonymizer`'s public promotion is holding up as a shared mechanism rather than accreting unrelated callers.

## Links

- Issue #35
- [ADR 0019](0019-deterministic-replay-algorithm-registry.md) / [ADR 0020](0020-replay-provenance-canonical-hash-and-catalog-versions.md) (replay, the sibling capability this deliberately does not duplicate), [ADR 0015](0015-policy-threshold-analyzer.md) (policy analyzer), [ADR 0012](0012-pseudonymous-subject-tokens-for-integration-events.md) (origin of `AccountPseudonymizer`, whose visibility guardrail this revises)
- Tests: `PolicyImpactAnalysisApplicationServiceTest`, `PolicyImpactControllerTest`, `PolicyImpactAnalysisIntegrationTest`, `AccountPseudonymizerTest`
