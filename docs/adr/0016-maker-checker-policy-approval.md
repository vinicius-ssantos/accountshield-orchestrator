# ADR 0016: Maker-checker approval for policy activation

- Status: Accepted
- Date: 2026-07-25

## Context

Issue #33 required separation of duties for policy activation: an author cannot approve their own policy, unapproved policies cannot become active, actor identity must come from the authenticated principal, rollback must target a previously-approved version, all lifecycle actions must be auditable, and concurrency must never allow two active versions of the same policy.

Before this change, a single actor could create, validate, and activate a policy version end-to-end. `PolicyLifecycleApplicationService.createDraft()` and `validate()` took no actor parameter at all — only `activate()`/`retire()` did, via the existing step-up-challenge ("privileged operation") flow introduced for policy activation/retirement. Nothing was persisted about who authored or signed off on a version; only `createdAt`/`activatedAt` timestamps existed.

Investigation found one piece already fully solved: a DB-level partial unique index, `uq_single_active_policy ON policy.policy_version (policy_key) WHERE status = 'ACTIVE'` (`V1__create_persistence_foundation.sql`), already guarantees at most one active version per policy key. The "concurrency tests prevent two active versions" acceptance criterion needed a **test proving this**, not new enforcement code.

No "rollback" concept exists anywhere in the codebase, and `RETIRED` is a terminal state — the `protect_activated_policy_version` DB trigger only allows `ACTIVE → RETIRED`, and `PolicyVersionEntity.validateTransition`'s state machine has no outgoing edge from `RETIRED`. Reactivating a retired version would mean reopening a terminal state, which contradicts ADR 0007's "activated policy versions are immutable" guarantee — a materially larger, separate decision.

## Decision

### A new `APPROVED` status, distinct from `VALIDATED`

`validate()` (ADR/issue #46) answers "is this policy internally sound" — deterministic, automatic, no human role. `approve()` answers "has an authorized human, other than the author, signed off on activating it" — a different concern, matching the issue's own three-role vocabulary (author / validator / approver). `PolicyStatus` gains `APPROVED` between `VALIDATED` and `ACTIVE`:

```
DRAFT → VALIDATED → APPROVED → ACTIVE → RETIRED
  ↓         ↓           ↓
REJECTED  REJECTED   REJECTED
```

`activate()`'s precondition changes from "current status is `VALIDATED`" to "current status is `APPROVED`" — this is what structurally satisfies "unapproved policies cannot become active": the state machine itself refuses the transition (`IllegalPolicyTransitionException`) for anything but `APPROVED`, the same way it already refused `DRAFT → ACTIVE` before this change.

### Actor identity and a `PolicyGovernance` value object

`createDraft()` and `validate()` now take an `actor` parameter (the two lifecycle entry points that previously had none), recorded as `createdBy` and `validatedBy`/`validatedAt` on the version. A new `approve(policyKey, version, stepUpChallengeId, actor, reason)` — gated by a step-up challenge exactly like `activate`/`retire` (new `ACTION_APPROVE`, no context-ID collision since `stepUpContextId` already parameterizes by action) — records `approvedBy`/`approvedAt`/`approvalReason`.

Rather than growing `PolicyVersionSummary`'s already-long positional-constructor chain (extended once in #46 for `analysis`) by six more fields, the whole governance trail is bundled into one new record, `policy.PolicyGovernance(createdBy, validatedBy, validatedAt, approvedBy, approvedAt, approvalReason)`, added as a single trailing field.

### Self-approval prevention

`approve()` checks the state-machine legality first (so an illegal-transition attempt reports `IllegalPolicyTransitionException`, not a stale self-approval verdict), then compares `actor` against the persisted `createdBy`. A match throws `SelfApprovalNotAllowedException`, mapped to `409`/`SELF_APPROVAL_NOT_ALLOWED` — mirroring exactly how `validate()` (#46) checks transition legality before running analysis, so no mutation happens on the rejected path.

### Auditability reuses existing plumbing

`approve()` publishes the existing `PrivilegedPolicyActionAttempted` event with `action = "APPROVE"` — the record already generically models `policyKey, version, action, actor, authorized`, so no new event type or listener wiring was needed; it flows into `SecurityEventLogger`'s existing structured `accountshield.security` log automatically, exactly like `ACTIVATE`/`RETIRE`. The full governance trail is persisted on the version row and surfaced through the existing `PolicyVersionSummary.governance()` in the same API responses.

### Concurrency: proving, not building, the guarantee

A new `PolicyActivationConcurrencyTest` races two different `APPROVED` candidate versions of the same policy key into `activate()` concurrently and asserts exactly one wins and exactly one row is `ACTIVE` afterward — proving the pre-existing `uq_single_active_policy` constraint holds under real concurrency, per the acceptance criterion, without adding new enforcement code.

## Alternatives considered

- **Folding approval into `validate()`** — rejected; conflates an automated structural check with a human sign-off, and the issue's own vocabulary (author/validator/approver) implies three distinct roles/stages.
- **Six new positional fields on `PolicyVersionSummary`** — rejected in favor of one `PolicyGovernance` value object, to stop the compatibility-constructor chain (already extended once in #46) from growing unreadable.
- **Implementing rollback-to-a-retired-version** — rejected for this PR; `RETIRED` is a terminal state guarded by a DB trigger, and reopening it is a materially larger, separate decision that would need its own ADR revisiting ADR 0007's immutability guarantee. The documented equivalent today: create a new draft copying the old version's thresholds and run it through the same author→validate→approve→activate pipeline.
- **Two-person ("critical policy class") approval** — explicitly optional in the issue text; skipped since no "policy class" concept exists in this codebase.

## Consequences

### Positive

- self-approval is now structurally impossible, not just a convention;
- an unapproved policy cannot reach `ACTIVE` — enforced by the state machine itself, not an ad hoc check;
- the full author/validator/approver trail is queryable via the existing API with no new endpoints beyond the two new approval-related ones;
- the existing single-active-version DB constraint is now proven under real concurrency, not merely assumed.

### Negative

- rollback to a retired historical version is still not supported — only creating a new draft from old thresholds;
- two-person/critical-class approval is not implemented, matching the issue's own "optional" framing;
- "routing scope" concurrency (multiple active versions per client/tenant) remains out of scope, owned by #26.

## Guardrails

- `PolicyVersionEntity.validateTransition` is the single source of truth for legal transitions; `activate()` has no bespoke "is this approved" check duplicating it;
- `approve()` never mutates governance fields before both the transition-legality check and the self-approval check pass;
- `PolicyActivationConcurrencyTest` asserts exactly one `ACTIVE` row for a shared policy key after concurrent `activate()` calls.

## Revisit criteria

This decision should be revisited when:

- true rollback (reactivating a `RETIRED` version) is requested — requires its own ADR relaxing ADR 0007's immutability guarantee;
- a "policy class" or tenant/client routing concept is introduced (#26) — would extend the uniqueness scope beyond `policy_key` and could activate two-person approval for a defined critical subset.

## Links

- Issue #33
- [ADR 0007](0007-policy-lifecycle-state-machine.md) (the state machine this extends), [ADR 0015](0015-policy-threshold-analyzer.md) (the `validate()` gate this builds on top of)
- [docs/architecture/README.md](../architecture/README.md) (policy module section)
- Tests: `PolicyLifecycleApplicationServiceTest`, `PolicyLifecycleControllerTest`, `PolicyLifecycleIntegrationTest`, `PolicyActivationConcurrencyTest`
