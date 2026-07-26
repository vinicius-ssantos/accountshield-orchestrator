# ADR 0022: Deterministic canary rollout splits traffic at the evaluation layer

- Status: Accepted
- Date: 2026-07-26

## Context

`docs/roadmap.md` Gate 4 sequences `#45→#21+#43→#46→#35→#34`, exit criterion "rollout decisions are deterministic, observable, and reversible." ADR 0007's own "Revisit criteria" explicitly names "staged/canary activation is required" as a trigger for this decision. `docs/features/README.md` marked this **Planned**: "Deterministic cohorts and progressive rollout are not implemented."

Direct code inspection found the central constraint: `PolicyLifecycleApplicationService.activate()` automatically retires the current `ACTIVE` version the instant a candidate activates (`repository.findByPolicyKeyAndStatus(policyKey, ACTIVE).ifPresent(current -> current.transitionTo(RETIRED, ...))`) — **only one `ACTIVE` version per policy key can ever exist**. A canary that keeps a stable and a candidate version simultaneously "live" therefore cannot be built with two `ACTIVE` rows; it must split traffic at the *evaluation* layer between the current `ACTIVE` version and an `APPROVED`-but-not-yet-`ACTIVE` candidate.

Other findings:

- `PolicyVersionEntity.transitionTo()`'s state machine only allows `APPROVED -> ACTIVE` — but that machine is never touched during a canary (the candidate stays `APPROVED` throughout), so "rollout cannot activate an unapproved policy" is not automatically inherited for this new path; it needs its own explicit check.
- `PolicyEvaluationService.evaluateVersion(policyKey, version, score, context)` (built for replay/#21 and impact-analysis/#35) already evaluates any version regardless of status — exactly what cohort-based candidate evaluation needs, with no policy-evaluation-layer changes required.
- No existing hash utility reduces to a bucket; `protection.RequestFingerprint` and `outbox.AccountPseudonymizer` solve different problems (canonical hashing, pseudonymization).
- Existing step-up asymmetry precedent: `approve()`/`activate()`/`retire()` (escalating/consequential) require step-up; `reject()` (de-escalating/stopping) does not.

## Decision

### One new table, one new module edge nowhere in sight

`policy.policy_rollout` (migration `V18`): one row per rollout attempt — `policy_key`, `candidate_version`, `rollout_percentage`, `status` (`ACTIVE`/`ROLLED_BACK`), `started_at`/`started_by`, `updated_at`, `rolled_back_at`/`rolled_back_by`. A partial unique index (`WHERE status = 'ACTIVE'`) enforces "only one active rollout per policy key" at the database level, mirroring the CHECK-constraint discipline already used for policy status (#33). No new module dependency is introduced: everything lives inside `policy`, called from `protection` (an edge that already exists).

### Deterministic, monotonic cohort assignment — no stored per-subject state

`policy.CohortAssignment.bucket(clientId, subject, policyKey)` hashes the three inputs (SHA-256, same byte-layout discipline as `RequestFingerprint`) to a fixed bucket in `[0, 99]`. Inclusion is `bucket < rolloutPercentage`. The bucket never changes for a given (clientId, subject, policyKey) triple regardless of percentage, which is what makes "changing rollout percentage produces predictable cohort expansion" true *by construction*: raising the percentage only ever adds subjects to the candidate cohort, never removes one already in it. No cohort-assignment table is needed — the function is pure and recomputed per decision.

### `decide()` gains one new branch, additive to `normalized_context`

`ProtectionDecisionApplicationService.decide()` looks up `PolicyRolloutService.findActiveRollout(policyKey)` once per decision. When present, it computes the cohort bucket and evaluates either the candidate (`evaluateVersion`) or the stable version (`evaluate`) — using the **exact same 2-arg/3-arg calling convention** the code used before this change for the "no rollout" path, so no existing test needed new stubs to keep passing. `normalized_context` gains four keys **only when a rollout is active**: `rolloutCohortBucket`, `rolloutCandidateVersion`, `rolloutPercentageAtDecision`, `rolloutCandidateSelected`. The already-recorded `policy_version` column continues to reflect whichever version actually decided (stable or candidate) — the new keys add *why*, not a duplicate of *what*.

### `PolicyRolloutService`: escalation requires step-up, de-escalation does not

Mirrors the existing asymmetry: `startRollout`/`updatePercentage` (escalating exposure to an unapproved-for-production version) require step-up via `ChallengeService`, exactly like `activate()`; `rollback` (de-escalating, must be immediate per the acceptance criteria) requires none, exactly like `reject()`. `startRollout` explicitly checks the candidate's `PolicyVersionEntity.status == APPROVED` (`RolloutCandidateNotApprovedException` otherwise) — this is the concrete enforcement of "rollout cannot activate an unapproved policy" for this new code path, since the existing state-machine guarantee does not cover it. Every step-up attempt (success or failure) publishes `PrivilegedPolicyActionAttempted`, reusing the existing security-observability event from #33/#48 rather than inventing a parallel one.

### `findActiveRollout` steps aside once a candidate is fully cut over

If an operator separately completes a full cutover via the existing `activate()` (candidate now `ACTIVE`, stable now `RETIRED`), `findActiveRollout` returns empty even if the bookkeeping row is still marked `ACTIVE` — the normal single-`ACTIVE`-version `evaluate()` path already produces the identical outcome for every subject at that point, so no explicit "complete" transition is required for correctness (see Consequences for the accepted bookkeeping loose end).

### Status endpoint stays inside `policy`; impact metrics stay a separate call to #35

`GET /api/v1/policies/{policyKey}/rollout` returns the `PolicyRollout` record. It deliberately does **not** bundle a `PolicyImpactReport` (#35) into the same response: `simulation` already depends on `policy` (confirmed by direct inspection of its imports), so a `policy -> simulation` edge to call `PolicyImpactAnalysisService` would create a module cycle. A caller wanting impact metrics for the canary makes a second call to the existing `POST /api/v1/simulation/policy-impact` endpoint using the `candidateVersion` from the rollout status response — composition of two existing capabilities, not a merged one, specifically to keep module boundaries acyclic.

### Metrics as the "automatic rollback hook," not automatic rollback itself

A Micrometer counter `accountshield.policy.rollout.decisions` (tags `policyKey`, `selection=stable|candidate`) is incremented whenever a rollout is active. This is the extension point the issue's "define metric-based automatic rollback hooks" asks for: an external alerting system can watch this counter (alongside the existing `accountshield.protection.degraded_decisions` and #35's impact analysis) and call the human/automation-triggered `rollback` endpoint. The system does not roll itself back from its own metrics — see Explicitly Deferred.

## Alternatives considered

- **Two simultaneously `ACTIVE` policy versions** — rejected outright; would require rewriting `activate()`'s single-`ACTIVE`-per-key invariant (ADR 0007) and every place that assumes it (`evaluate()`'s `findByPolicyKeyAndStatus`), a far larger and riskier change than splitting traffic at evaluation time.
- **Bundling impact metrics into the rollout status response** — rejected; would require a `policy -> simulation` edge, creating a cycle since `simulation` already depends on `policy`.
- **Fully automatic metric-triggered rollback** — rejected; the system deciding to change production routing based on its own signals with no human in the loop is a materially different, higher-blast-radius mechanism deserving its own ADR and threshold-tuning discussion, not a bolt-on here.
- **A scheduled "effective period" (auto-start/auto-expire)** — rejected for this slice; would need a new scheduled job mirroring `ChallengePlanRetentionCleanup`/`RecoveryFlowRetentionCleanup`. An unenforced `endAt` field would be misleading, so none was added.

## Consequences

### Positive

- a candidate version can now serve a deterministic, monotonically-expanding fraction of live traffic without ever touching the immutable `ACTIVE`/`RETIRED` state machine ADR 0007 established;
- every rollout-influenced decision is explainable after the fact from `normalized_context` alone — cohort bucket, percentage in effect at decision time, and which version was actually selected;
- rollback is a single, step-up-free, immediate write, consistent with the acceptance criteria and the existing `reject()` precedent.

### Negative

- a rollout row left `ACTIVE` after an operator separately completes a full cutover via `activate()` is a harmless but slightly confusing bookkeeping loose end (percentage says "100%, still rolling out" when the version is in fact now permanently `ACTIVE`) — accepted as a known, non-correctness-affecting gap rather than adding lifecycle coupling between `PolicyLifecycleApplicationService.activate()` and rollout bookkeeping.
- `findActiveRollout` costs one extra `PolicyVersionRepository` read per decision whenever a rollout row exists for the resolved policy key (to confirm the candidate hasn't been cut over) — zero cost when no rollout is active, which is the common case.

## Guardrails

- `ux_policy_rollout_active_per_key` (partial unique index) makes a second concurrent active rollout for the same policy key a database-level impossibility, not just an application-level check;
- `startRollout` fails closed (`RolloutCandidateNotApprovedException`) unless the candidate's persisted status is exactly `APPROVED` at the moment rollout starts;
- `rollback` never calls `ChallengeService` — verified by `DatabasePolicyRolloutServiceTest.rollbackRequiresNoStepUpAndIsImmediate` asserting `verifyNoInteractions(challengeService)`;
- raising `rolloutPercentage` never removes a subject already in the candidate cohort — verified by `CohortAssignmentTest`'s monotonic-expansion property test across 500 random subjects.

## Migration/compatibility implications

New table only (`V18__add_policy_rollout.sql`); no existing column or table is altered. `ProtectionDecisionApplicationService`'s constructor gained a new required `PolicyRolloutService` parameter — every direct-construction test site was updated (three call sites across two test files) to inject a rollout-service double; behavior for callers with no active rollout is byte-for-byte unchanged (confirmed by preserving the original 2-arg/3-arg `evaluate()` calling convention for that path).

## Revisit criteria

This decision should be revisited when:

- scheduled effective-period auto-start/auto-expire is picked up as its own issue;
- fully automatic metric-triggered rollback is deliberately designed as a separate, higher-scrutiny change;
- the "stale `ACTIVE` rollout row after manual cutover" bookkeeping gap is judged worth closing (e.g. `activate()` auto-completing a matching rollout row).

## Links

- Issue #34
- [ADR 0007](0007-policy-lifecycle-state-machine.md) (the single-`ACTIVE`-version invariant this respects rather than changes), [ADR 0016](0016-maker-checker-policy-approval.md) (maker-checker approval this depends on), [ADR 0021](0021-historical-policy-impact-analysis.md) (impact metrics reused via composition, not merged)
- Tests: `CohortAssignmentTest`, `DatabasePolicyRolloutServiceTest`, `PolicyRolloutControllerTest`, `PolicyRolloutIntegrationTest`, `ProtectionDecisionApplicationServiceTest`
