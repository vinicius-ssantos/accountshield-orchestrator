# ADR 0015: Deterministic policy-threshold analyzer as a validation gate

- Status: Accepted
- Date: 2026-07-25

## Context

Issue #46 asked for a static analyzer and linter for policy definitions detecting unreachable/shadowed rules, duplicated priorities, contradictory conditions, unknown signal/reason-code references, and cycles — the vocabulary of a rich, rule-based policy DSL with conditions, priorities, and per-rule reason codes.

Investigation found that this DSL does not exist. A policy in this codebase (`policy.internal.persistence.PolicyVersionEntity`) is exactly three nullable numeric thresholds (`allowMaxScore`, `stepUpMaxScore`, `recoveryMaxScore`), and evaluation (`policy.internal.DatabasePolicyEvaluationService`) is a hardcoded three-branch comparison against a risk score. There are no rules, conditions, priorities, or reason-code references anywhere in the module. `docs/features/README.md` already documented this honestly: *"Static policy analysis | Planned | Semantic diagnostics such as shadowed and contradictory rules are not implemented | #46."*

The real, concrete gap was different from the issue's literal scope: `PolicyLifecycleApplicationService.validate()` (the `DRAFT → VALIDATED` transition) was a bare status flip with zero semantic re-check. All bounds/ordering checks ran once, only at `createDraft`, as `IllegalArgumentException`s outside the policy problem-detail vocabulary — and nothing caught a **null** threshold pre-activation (a policy missing `allowMaxScore`/`stepUpMaxScore`/`recoveryMaxScore` only surfaces as `ActivePolicyUnavailableException` live, during a real decision or recovery request).

## Decision

### Scope: analyze the threshold model that exists today

`policy.PolicyAnalyzer` performs deterministic, side-effect-free analysis of a `PolicyDefinition` (the three nullable thresholds), producing a `PolicyAnalysisResult` (`analyzerVersion` + ordered `PolicyDiagnostic` list, each diagnostic carrying a stable `code`, `severity` (`ERROR`/`WARNING`), `path`, and `message`). Diagnostics implemented:

| Code | Severity | Meaning |
| --- | --- | --- |
| `ALLOW_MAX_SCORE_MISSING` / `STEP_UP_MAX_SCORE_MISSING` / `RECOVERY_MAX_SCORE_MISSING` | ERROR | threshold is `null` — the concrete instance of "missing default behavior": a policy that would fail closed unpredictably only when it is actually evaluated |
| `ALLOW_MAX_SCORE_OUT_OF_RANGE` / `STEP_UP_MAX_SCORE_OUT_OF_RANGE` / `RECOVERY_MAX_SCORE_OUT_OF_RANGE` | ERROR | threshold present but outside its valid range |
| `STEP_UP_BAND_SHADOWED` | ERROR | `allowMaxScore >= stepUpMaxScore` — the `REQUIRE_STEP_UP` band has zero width and is entirely shadowed by `ALLOW`; the literal "unreachable and shadowed rules" criterion, reinterpreted for the threshold model |
| `RECOVERY_THRESHOLD_MORE_RESTRICTIVE_THAN_ALLOW` | WARNING | `recoveryMaxScore < allowMaxScore` — non-blocking heuristic; recovery is evaluated independently of standard `ALLOW`, so this is suspicious but not structurally contradictory |

`stepUpMaxScore`'s valid range is enforced as `[1, 99]` (matching the existing `validateCreateCommand` convention), which means the `TEMPORARILY_BLOCK` band (`stepUpMaxScore+1 .. 100`) can never be empty for a value the range check accepts — a distinct "unreachable terminal band" diagnostic was considered and dropped as dead code once this was verified: it can never fire without already being caught by the out-of-range check.

Deliberately **not implemented**: duplicated priorities, contradictory conditions across rules, unknown signal/reason-code references, cycles — none apply because no rule/condition/reference model exists. These require a real policy rule DSL, which is a materially larger, separate change.

### The gate: `validate()` now actually validates

`PolicyLifecycleApplicationService.validate()` runs the analyzer against the candidate's persisted thresholds. If any diagnostic is `ERROR` severity, it throws `PolicyAnalysisFailedException` (mapped to `422`/`POLICY_ANALYSIS_FAILED` with the diagnostics list as a Problem Details extension) and the entity **stays `DRAFT`** — a corrected re-`validate()` call is naturally retryable, since `transitionTo` validates the state-machine edge before mutating anything. Only on a clean (zero-error) analysis does the entity transition to `VALIDATED`, with the analysis persisted onto it at that moment.

### Persistence: versioned and auditable, no new table

A nullable `analysis JSONB` column is added to `policy.policy_version` (migration `V15`), populated exactly once per version — at the moment it successfully validates — with `{"analyzerVersion", "diagnostics"}`. This reuses the JSONB-extension pattern established for `normalized_context` (ADR 0013, ADR 0014) rather than a new audit table: `DRAFT` rows are mutable and re-validatable, so there is no stable "attempt" identity to anchor separate history rows to, and a rejected `validate()` call's diagnostics are already visible in its `422` response.

### API, not CLI

A new stateless `POST /api/v1/policies/analyze` endpoint runs the analyzer against an arbitrary candidate definition with no persistence and no side effects, covered by the existing `/api/v1/policies/**` → `POLICY_ADMIN` security rule. No CLI exists anywhere in this codebase today (only `AccountShieldApplication.main`); building one here would duplicate issue #56 ("Create an AccountShield scenario CLI"), which owns that concern. The new endpoint is built so a future CLI can call the identical code path and get identical findings.

## Alternatives considered

- **Building the full rule/condition/signal-reference DSL first** — rejected as disproportionate; no other open issue requests it, and the acceptance criteria are honestly satisfiable against the threshold model that exists.
- **jqwik-based property tests** — rejected; a new test dependency can't be verified locally (no Maven in this environment), and the finite `Short` input space (`0..100` plus `null`) is already exhaustively covered by ordinary boundary-loop JUnit tests.
- **A separate audit table for every validate attempt, including rejected ones** — rejected; `DRAFT` rows are mutable and re-validatable, so there is no stable identity for a "rejected attempt" record, and the `422` response already carries the rejection's diagnostics.

## Consequences

### Positive

- `validate()` is now a real, reusable, versioned gate instead of a bare status flip;
- a policy with a missing/contradictory threshold is now rejected before activation instead of failing unpredictably, live, during a real decision;
- the analyzer's code path is directly reusable by a future CLI (#56) via the new API endpoint.

### Negative

- rule/condition/signal-reference/cycle diagnostics from the issue's literal text are not implemented — they require a policy DSL that doesn't exist yet;
- `validate()` is now a behavioral change: a well-formed-looking `DRAFT` row created outside the normal `createDraft` path (e.g. direct DB manipulation) can now fail to validate where it previously always succeeded.

## Guardrails

- `PolicyAnalyzer` has no Spring dependencies and performs no I/O — verified by `PolicyAnalyzerTest`'s determinism assertion;
- `validate()` never transitions to `VALIDATED` when `PolicyAnalysisResult.hasErrors()` is true — verified by `PolicyLifecycleApplicationServiceTest` and the Testcontainers integration test;
- the state-machine legality check (`IllegalPolicyTransitionException`) still takes precedence over analysis errors — validating an already-`ACTIVE`/`RETIRED`/`REJECTED` version reports the illegal transition, not a stale analysis of its thresholds.

## Revisit criteria

This decision should be revisited when:

- a real policy rule DSL (conditions, priorities, signal/reason-code references) is introduced — the deferred diagnostics in the issue's original scope become implementable then;
- issue #56's CLI lands — it should call `POST /api/v1/policies/analyze` directly rather than reimplementing analysis.

## Links

- Issue #46
- [ADR 0007](0007-policy-lifecycle-state-machine.md) (lifecycle state machine this gate extends), [ADR 0013](0013-risk-signal-provenance-envelope.md), [ADR 0014](0014-explicit-degradation-strategies-for-dependency-failures.md) (JSONB-extension and versioned-artifact precedent this reuses)
- [docs/architecture/README.md](../architecture/README.md) (policy module section)
- Tests: `PolicyAnalyzerTest`, `PolicyLifecycleApplicationServiceTest`, `PolicyLifecycleControllerTest`, `PolicyLifecycleIntegrationTest`
