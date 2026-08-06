# ADR 0054: Operator policy rollout controls as the third console mutation

- Status: Accepted
- Date: 2026-08-02

## Context

Issue #200 wires the read-only policy investigation console (issue #182/#183) to the backend's
existing canary rollout flow (start rollout with a percentage, adjust percentage, roll back --
issue #34): the third privileged mutation exposed by the operator console, after recovery review
(#194, ADR 0053) and policy lifecycle approve/activate/reject/retire (#197).

Building this surfaced the same backend gap already fixed twice before: `PolicyRolloutController`'s
two step-up endpoints (start-rollout step-up, percentage-update step-up) always returned
`simulatedCode: null` and `contextId: null`, so no real HTTP client could ever complete
`POST /api/v1/challenges/{id}/verify`. This is fixed separately (see the backend PR referenced
below), reusing the existing `PolicySimulatedStepUpCodeCapture` bean introduced for policy
lifecycle in #198 -- unlike that fix, no new capture class was needed here, since the existing one
is already generic (keyed only on `challengeId`, with no lifecycle-specific action hardcoding).

## Decision

The BFF mutation module (`server/bff/policy-rollout-core.ts`, `policy-rollout.ts`,
`policy-rollout-fixtures.ts`, five routes under `app/api/bff/policy-rollout/`) mirrors policy
lifecycle's structure and reuses `require-session.ts`'s `requireOperatorSession` directly -- the
same "no env-token fallback for mutations" rule established in ADR 0053 applies here too.

Step-up verification does **not** get its own BFF route. Both rollout step-up flows use
`ChallengePurpose.PRIVILEGED_OPERATION`, identical to policy lifecycle's, and the backend's
`POST /api/v1/challenges/{id}/verify` endpoint is generic -- it does not distinguish "lifecycle" vs.
"rollout" challenges beyond the `challengeId`/`contextId` pair the corresponding `.../step-up`
endpoint disclosed. `PolicyRolloutActions` (the UI component) therefore calls
`policy-lifecycle-browser.ts`'s existing `verifyLifecycleStepUp`, avoiding a duplicate route,
handler, and test suite for behavior that is byte-for-byte the same request/response shape.

Rollback is deliberately **not** step-up-gated, matching the backend contract
(`PolicyRolloutController.rollback` takes no `stepUpChallengeId`) -- it is framed in the UI as an
immediate, no-undo action with its own distinct confirmation step (a `SafeAlert` with explicit
"takes effect immediately... no undo" copy and a visually distinct `.button--critical` control),
not the same step-up confirmation flow used by start/adjust.

`src/features/policies/` was already removed from `architecture.config.mjs`'s `readOnlyScopes` in
#197 (ADR for that change was not separately written; the scope removal already covers this
feature directory) -- no further architecture-config change is required.

Two new `BffErrorCode` values are added: `ROLLOUT_ALREADY_ACTIVE` and
`ROLLOUT_CANDIDATE_NOT_APPROVED`. Rollout's problem catalog does not include self-approval or
generic illegal-transition concepts (a canary rollout has no "author" to self-approve against), so
`SELF_APPROVAL_NOT_ALLOWED`/`ILLEGAL_TRANSITION` are not reused here, despite both being
documented in `model.ts` as intentionally generic/reusable.

## Alternatives considered

### Add a dedicated `/api/bff/policy-rollout/verify` route mirroring policy lifecycle's

Rejected: the underlying backend endpoint and request/response shape are identical to policy
lifecycle's verify route with no rollout-specific behavior; a second route would be a pure
duplicate to maintain and test with no behavioral benefit.

### Require step-up for rollback, matching start/adjust

Rejected: the backend contract (`PolicyRolloutController.rollback`, `PolicyRolloutService`) does
not accept or require a `stepUpChallengeId` for rollback -- introducing one on the frontend side
would misrepresent the actual authorization model. The UI compensates with an explicit,
visually-distinct immediate-action confirmation instead.

### Reuse `SELF_APPROVAL_NOT_ALLOWED`/`ILLEGAL_TRANSITION` for rollout conflicts

Rejected: rollout's actual conflict states (`ROLLOUT_ALREADY_ACTIVE`, `ROLLOUT_CANDIDATE_NOT_APPROVED`)
are semantically distinct from a maker-checker rejection or a lifecycle-state transition failure;
forcing them into the existing codes would produce a misleading UI message.

## Consequences

### Positive

- the third console mutation ships with the same session/CSRF guarantees and no fixtures/dev
  bypass as the two before it, continuing to prove #78's session infrastructure against real
  state-changing traffic;
- the simulated-code disclosure gap is fixed at its source for the third time, closing out the
  last of the three known call sites (recovery review, policy lifecycle, policy rollout) noted as
  a known gap since #195;
- reusing the existing step-up verify route and `PolicySimulatedStepUpCodeCapture` bean avoided
  introducing any new Spring bean-name collision risk or duplicate frontend code.

### Negative

- rollback's lack of step-up is an intentional backend design choice inherited as-is, not
  something this issue could change -- the UI's distinct confirmation styling is a compensating
  control, not equivalent security to a step-up gate;
- no client-side role-based hiding, matching the precedent set in ADR 0053 -- an unauthorized
  operator briefly sees rollout controls that will then fail against the backend's own role check.

## Guardrails

- policy-rollout BFF routes must continue to call `requireOperatorSession`, never
  `resolveOperatorToken`;
- the simulated code must never be logged (see `policy-rollout-core.test.ts`'s and
  `policy-rollout.test.ts`'s leakage-proof coverage, mirroring policy-lifecycle's);
- `architecture:check` must continue to pass with `src/features/policies/` outside
  `readOnlyScopes`.

## Revisit criteria

Revisit if: a fourth mutation with the same maker-checker/state-machine shape needs its own
distinct `BffErrorCode` pair, following the pattern established by
`SELF_APPROVAL_NOT_ALLOWED`/`ILLEGAL_TRANSITION` and now `ROLLOUT_ALREADY_ACTIVE`/
`ROLLOUT_CANDIDATE_NOT_APPROVED`; or the backend ever adds step-up to rollback, requiring the UI
flow to change to match.

## References

- Issue #200 -- Implement operator policy rollout controls (start/adjust/rollback) through the BFF.
- Issue #197 / policy lifecycle mutation -- the direct structural template for this module.
- Issue #194 / ADR 0053 -- the first console mutation and the origin of the
  `requireOperatorSession`-only rule.
- ADR 0004 -- challenge orchestration via simulated providers (context for the step-up disclosure
  fix).
- `frontend/src/server/bff/policy-rollout-core.ts`, `policy-rollout.ts`,
  `policy-rollout-fixtures.ts`, `frontend/src/features/policies/policy-rollout-browser.ts`,
  `policy-rollout-actions.tsx`.
