# ADR 0055: Operator outbox dead-letter requeue as the fourth console mutation, and the first with no step-up gate

- Status: Accepted
- Date: 2026-08-02

## Context

Issue #203 wires the read-only outbox delivery health console (#74/#188) to the backend's existing
`POST /api/v1/outbox/{eventId}/requeue` endpoint, letting an operator manually requeue a single
dead-lettered event. This is the fourth console mutation, after recovery review (#194, ADR 0053),
policy lifecycle (#197), and policy rollout (#200, ADR 0054).

Unlike all three prior mutations, this backend endpoint required **no changes**: it already existed
on `main`, already guards that the target event is currently `DEAD_LETTERED`
(`OutboxEventNotDeadLetteredException`, mapped to `409 OUTBOX_EVENT_NOT_DEAD_LETTERED`), already
has a `404 OUTBOX_EVENT_NOT_FOUND` case, and -- notably -- requires only the `SECURITY_OPERATOR`
role, with **no step-up challenge**. Every prior mutation ADR treated a fresh step-up challenge as
the norm for a privileged action; this is the first case where that norm doesn't apply, and it's
worth recording why rather than silently deviating from the established pattern.

## Decision

Requeuing a dead-lettered outbox event is **operational remediation**, not a privileged security
action: it does not change any account-protection decision, policy, or recovery outcome -- it only
resets delivery bookkeeping (`status`, `nextAttemptAt`, `attemptCount`) so the existing relay picks
the event back up. The blast radius of an accidental or malicious requeue is bounded (at most a
duplicate publish attempt, which the outbox's own at-least-once delivery model already tolerates by
design -- see ADR 0009). This is qualitatively different from approving a policy version, starting a
canary rollout, or approving a recovery, each of which directly changes what the system decides or
who gets access. The backend's own authorization model reflects this distinction (role-gated, no
step-up), and the frontend mutation module mirrors it rather than inventing a step-up flow the
backend contract doesn't support.

The BFF mutation module (`server/bff/outbox-requeue-core.ts`, `outbox-requeue.ts`,
`outbox-requeue-fixtures.ts`, one route at `app/api/bff/outbox-requeue/`) still reuses
`require-session.ts`'s `requireOperatorSession` -- the "no env-token fallback for mutations" rule
from ADR 0053 applies regardless of whether the action itself needs step-up. CSRF and origin
validation are unconditional for this route, same as every other mutation.

The UI (`RequeueControl` in `outbox-console.tsx`) adds a lightweight, single-step confirmation
("Requeue this event now? Confirm / Cancel") before submitting -- not a step-up flow, since none
exists, but still a deliberate pause before a state-changing action, consistent with rollout's
rollback control (ADR 0054) even though the underlying authorization mechanism differs.

`src/features/outbox/` is removed from `architecture.config.mjs`'s `readOnlyScopes` -- the fourth
feature directory (after recoveries, policies) to lose that guarantee.

A new `BffErrorCode`, `OUTBOX_EVENT_NOT_DEAD_LETTERED`, is added for the one real conflict case:
the event is no longer dead-lettered by the time requeue is attempted (already requeued by another
operator, or delivered in the interim).

## Alternatives considered

### Add a step-up gate to this action anyway, for consistency with the other three mutations

Rejected: the backend contract does not accept or require a `stepUpChallengeId` for this endpoint.
Inventing one on the frontend would misrepresent the actual authorization model, exactly the
reasoning ADR 0054 already used to reject step-up for rollback. Consistency with *this codebase's
actual security model* takes priority over surface consistency with prior mutations' UI shape.

### Treat this as a read-adjacent action and leave `src/features/outbox/` in `readOnlyScopes`

Rejected: it is unambiguously a mutation (it changes persisted outbox event state), and the
architecture linter's read-only-scope guarantee exists specifically to catch exactly this kind of
change, not to be preserved by mislabeling.

## Consequences

### Positive

- the fourth console mutation ships with the same session/CSRF guarantees as the three before it;
- this ADR documents, for the first time in this project, the criterion for *when a mutation does
  not need step-up* (operational remediation vs. a decision/access-changing action), giving a clear
  precedent for any future mutation of this shape (e.g. a future "cancel a queued replay" or similar
  operational action);
- no backend PR was required, since the endpoint, guard, and error catalog already existed and were
  already covered by backend tests.

### Negative

- no client-side role-based hiding, matching the precedent set in ADR 0053 -- an unauthorized
  operator briefly sees the requeue control before the backend's own role check rejects the action;
- the lightweight confirm/cancel UI is a UX nicety, not a security control -- a compromised or
  careless operator session can requeue events with a single extra click, unlike the three prior
  mutations' step-up gate.

## Guardrails

- the outbox-requeue BFF route must continue to call `requireOperatorSession`, never
  `resolveOperatorToken`;
- `architecture:check` must continue to pass with `src/features/outbox/` outside `readOnlyScopes`.

## Revisit criteria

Revisit if: the backend ever adds a step-up requirement to this endpoint (the UI flow would need to
change to match, as ADR 0054 already anticipated for rollback); or a future mutation candidate is
ambiguous about whether it counts as "operational remediation" vs. a privileged action, in which
case this ADR's criterion should be made more precise rather than re-litigated per issue.

## References

- Issue #203 -- Implement operator outbox dead-letter requeue through the BFF.
- Issue #194 / ADR 0053 -- the first console mutation and the origin of the
  `requireOperatorSession`-only rule.
- Issue #200 / ADR 0054 -- the immediately preceding mutation and the rollback no-step-up precedent
  this ADR extends.
- ADR 0009 -- outbox relay with simulated publisher (at-least-once delivery tolerance context for
  why a duplicate publish from requeue is an accepted, not exceptional, outcome).
- `frontend/src/server/bff/outbox-requeue-core.ts`, `outbox-requeue.ts`, `outbox-requeue-fixtures.ts`,
  `frontend/src/features/outbox/outbox-requeue-browser.ts`, `outbox-console.tsx`.
