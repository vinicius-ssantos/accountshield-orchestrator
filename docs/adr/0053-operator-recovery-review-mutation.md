# ADR 0053: Operator recovery review as the first console mutation

- Status: Accepted
- Date: 2026-08-01

## Context

Issue #194 wires the read-only recovery investigation console (issues #71/#174) to the backend's
existing recovery review flow (fresh step-up challenge, then approve/reject), making it the first
privileged mutation exposed by the operator console. Every prior console feature (#69-#74, and
#78's session work itself) was either read-only or infrastructure with no route calling it yet.

Building this surfaced a real backend gap: the simulated challenge code issued by
`POST /recovery/{id}/review/step-up` (ADR 0004's simulated providers) is hashed at rest
immediately and never returned through any endpoint -- there was no production-safe way for any
real client to learn it and complete the required `POST /challenges/{id}/verify` step. This is
fixed separately (see the backend PR referenced below) by disclosing the code only when
`accountshield.challenge.simulation-enabled=true`, narrowly scoped to the recovery-review path.

## Decision

The BFF mutation module (`server/bff/recovery-review-core.ts`, `recovery-review.ts`, three routes
under `app/api/bff/recovery-review/`) reuses `require-session.ts`'s `requireOperatorSession`
directly -- not `resolveOperatorToken` -- so every mutating call unconditionally requires a real
session and passes CSRF/origin validation. There is deliberately **no env-token fallback** for
mutations: `resolveOperatorToken`'s fixtures/dev convenience is appropriate for reads, not for an
action that changes recovery state.

`src/features/recoveries/` is removed from `architecture.config.mjs`'s `readOnlyScopes` -- it now
legitimately contains a mutation (`recovery-review-browser.ts`, `recovery-review-panel.tsx`). This
is the first feature directory to lose that guarantee since the read-only-first product principle
was established.

The step-up UI discloses the simulated code directly in the page (labeled explicitly as a
simulated-provider artifact, referencing ADR 0004) rather than pretending a real out-of-band
channel exists, since none does in this portfolio.

Client-side role gating (hiding the review action for non-`SECURITY_OPERATOR` operators) is
**not** implemented in this PR -- the review action is always rendered for a `MANUAL_REVIEW`
recovery, and unauthorized attempts are rejected by the BFF session guard and the backend's own
role check, independently. Per this project's "UI capability mapping is UX, not authorization"
principle (see `features/session/capabilities.ts`), this is a valid, if less polished, way to
satisfy the security requirement; role-based hiding can be added later without any security
implication either way.

A reason/note field for the review decision is **not** added to the backend contract in this PR.
The existing audit trail (reviewer identity from the authenticated principal, `RecoveryCompleted`
event, decision-trace/outbox projections) already captures who reviewed and what happened;
free-text justification capture is deferred to a future issue if a real need for it emerges.

## Alternatives considered

### Allow env-token fallback for mutations, matching read routes

Rejected: a fixtures/dev convenience that lets an unauthenticated BFF process act as an operator
is a reasonable trade-off for a read, but not for an action that changes recovery state -- the
blast radius of a misconfigured fallback flag is categorically different.

### Keep `src/features/recoveries/` read-only and put the mutation in a new sibling feature directory

Rejected: the review action is intrinsically part of investigating and acting on a recovery in
`MANUAL_REVIEW`; splitting it into a separate feature would fragment the UI and the BFF module
boundary without a real isolation benefit, and the architecture linter's read-only-scope guarantee
is meant to protect features that truly have no mutation, not to be preserved by relocating code
around it.

### Add a database-backed reason/justification field to the review contract now

Rejected as premature: no acceptance criterion in #194 requires it, and the existing audit trail
already answers "who reviewed this and what did they decide." Revisit if a real compliance/audit
need for free-text justification emerges.

## Consequences

### Positive

- the operator console gains its first real, working privileged action, proving the #78 session
  infrastructure end to end (CSRF, origin validation, backend-token forwarding) against genuine
  state-changing traffic, not just reads;
- the simulated-code disclosure gap is fixed at its source (a small, narrowly-scoped backend
  change) rather than worked around in the frontend;
- mutations remain strictly session-gated with no fixtures/dev bypass, keeping the blast radius of
  a misconfiguration bounded.

### Negative

- `src/features/recoveries/` no longer has the architecture linter's read-only guarantee -- future
  changes to that directory must be reviewed as potentially mutating, not assumed safe;
- no client-side role-based hiding means an unauthorized operator briefly sees a review action
  that will then fail -- a minor UX rather than security cost;
- protection step-up and recovery-identity confirmation still cannot disclose their simulated
  codes to a real client; only the recovery-review path was fixed.

## Guardrails

- recovery-review BFF routes must continue to call `requireOperatorSession`, never
  `resolveOperatorToken`;
- the simulated code must never be logged (see `recovery-review-core.test.ts`'s and
  `recovery-review.test.ts`'s leakage-proof coverage);
- `architecture:check` must continue to pass with `src/features/recoveries/` outside
  `readOnlyScopes` -- if it is ever re-added, this ADR's decision has been reversed and should be
  superseded, not silently ignored.

## Revisit criteria

Revisit if: a second mutation is added to the recoveries feature and a shared confirmation/step-up
UI pattern would reduce duplication; client-side role gating becomes worth the cross-feature
import cost; or a real compliance need for reviewer-supplied justification emerges.

## References

- Issue #194 -- Implement operator recovery review (approve/deny) through the BFF with fresh
  step-up.
- Issue #78 / ADR 0052 -- the session/CSRF infrastructure this mutation is the first real
  consumer of.
- ADR 0004 -- challenge orchestration via simulated providers (context for the step-up disclosure
  fix).
- `frontend/src/server/bff/recovery-review-core.ts`, `recovery-review.ts`,
  `frontend/src/features/recoveries/recovery-review-browser.ts`, `recovery-review-panel.tsx`.
