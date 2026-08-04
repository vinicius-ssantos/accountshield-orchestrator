# ADR 0021: Operator evidence export as the fifth console mutation, and the first with a read/mutation split inside one feature

- Status: Accepted
- Date: 2026-08-04

## Context

Issue #214 wires the read-only decision investigation console to the backend's existing signed,
redacted evidence bundle service (issue #42, ADR 0028): `POST /api/v1/evidence/export`
(`{protectionRequestId, reason}` → `EvidenceBundle`) and `POST /api/v1/evidence/verify`
(`EvidenceBundle` → `{valid, problems}`). This is the fifth console mutation, after recovery
review (#194, ADR 0018), policy lifecycle (#197), policy rollout (#200, ADR 0019), and outbox
dead-letter requeue (#203, ADR 0020).

No backend changes were required: both endpoints, their `SECURITY_OPERATOR` role gate, the
append-only `audit.evidence_export_log` write, and the no-step-up posture already existed and
were already covered by `EvidenceBundleSignerTest`, `EvidenceExportControllerTest`, and
`EvidenceBundleIntegrationTest` on `main`.

Unlike every prior mutation, this issue adds **two** BFF routes for one feature that are
classified differently: `export` performs one durable write (an `audit.evidence_export_log`
row) and must be treated as a mutation; `verify` recomputes a hash and checks a signature
(`EvidenceBundleApplicationService.verify` is `@Transactional(readOnly = true)` and touches no
repository) and is side-effect-free, exactly like `decision-replay`. Prior mutation issues always
had a single, uniformly-classified action per feature; this is the first case needing a split.

## Decision

### `evidence-export` is a mutation; `evidence-verify` is a read

`server/bff/evidence-export.ts` uses `requireOperatorSession` (no env-token fallback, CSRF and
origin validation unconditional for the route), matching every mutation module since ADR 0018.
`server/bff/evidence-verify.ts` uses `resolveOperatorToken`, matching `decision-replay.ts`'s
classification -- fixtures/dev env-token fallback allowed, same as any other read.

This does **not** mean `verify` skips CSRF outright: `resolveOperatorToken` calls
`requireOperatorSession` first and only falls back to the env token when that call fails with
`UNAUTHORIZED` (no session at all). A CSRF/origin failure on an *existing* session throws
`FORBIDDEN`, which is never caught by the fallback -- so a genuine operator session on this route
still enforces CSRF on every state-changing HTTP method, exactly as it already does for
`decision-replay`. This is pre-existing behavior of `resolveOperatorToken`, not something new
introduced here; this ADR just makes explicit that `evidence-verify` inherits it.

### No step-up gate, on both routes

Following ADR 0020's criterion (operational remediation / non-decision-changing action vs. a
privileged action that changes what the system decides or who gets access), evidence export is
squarely on the "no step-up" side: it creates no new authorization, activates no policy, and
changes no account-protection outcome. The manifest's signature and content hash -- not a fresh
challenge -- *are* this feature's actual security control, per ADR 0028: a bundle is tamper-evident
and independently verifiable by construction, which is a stronger guarantee for *this specific
risk* (was the exported evidence altered after the fact?) than a step-up challenge would add.
`verify` needs no gate beyond authentication for the same reason `decision-replay` doesn't: it is
read-only and its result depends only on the caller-supplied bundle, not on any privileged state
transition.

### The panel round-trips both endpoints in one flow

`features/decisions/evidence-export-panel.tsx` captures a required reason (1–500 characters,
mirroring `EvidenceExportCommand`'s own validation), calls `export`, displays the manifest
summary (decision id, exported-by, reason, generated-at, content hash, signature algorithm) and
offers a "Download bundle (JSON)" action (`Blob` + `URL.createObjectURL`, no new dependency), and
a "Verify bundle" action that sends the just-exported bundle to `verify` and shows the resulting
valid/invalid state with the backend's own problem strings. This demonstrates issue #42's
acceptance criterion ("provide a verification CLI or endpoint") end-to-end from the console
without building a separate upload/paste UI for verification -- an operator can still verify an
externally-received bundle by pasting its JSON into a future extension of this flow if needed, but
that was not required for this issue's scope.

### `src/features/decisions/` loses its `readOnlyScopes` guarantee

Every decision-search, timeline, and replay flow remains genuinely read-only (ADR 0006's
side-effect-free guarantee is unchanged); only evidence export writes anything, and only to its
own dedicated audit log, never to `decision_trace` or any other table. The three existing ARCH007
exceptions scoped under this directory (`decision-search-browser.ts`,
`decision-timeline-browser.ts`, `decision-replay-browser.ts`, all justifying a same-origin POST
body from a nominally read-only path) are removed rather than left in place: ARCH007 only fires
for paths inside `readOnlyScopes`, so once the directory leaves that list the rule no longer
applies there at all, and the entries would otherwise fail `architecture:check`'s ARCH011
stale-exception validation -- the same fix already applied when `src/features/outbox/` left
`readOnlyScopes` in ADR 0020.

### No new `BffErrorCode`

Unlike the three step-up-era mutations and outbox requeue, evidence export has no distinct
conflict vocabulary to add: `export` either finds the protection request (200) or it doesn't
(404); its only other backend-specific failure is `EVIDENCE_INVALID_REQUEST` (malformed
`protectionRequestId`/`reason`), which folds into the existing generic `INVALID_REQUEST` code
rather than earning its own, since the UI already has a specific, actionable message for "the
export reason is invalid" without needing a dedicated code to key off of.

## Alternatives considered

### Classify `verify` as a mutation too, for consistency with `export`

Rejected: `verify` performs no write and its outcome depends only on the caller-supplied bundle,
not on any persisted or privileged state -- classifying it as a mutation would misrepresent the
backend's own transactional boundary (`@Transactional(readOnly = true)`) and needlessly deny the
fixtures/dev env-token fallback to a route that has no real state-changing risk to gate.

### Add a step-up gate to `export`, for consistency with the earlier three mutations

Rejected for the same reason ADR 0019 and ADR 0020 already rejected it for rollback and requeue:
the backend contract does not require a challenge for this endpoint, and ADR 0028 deliberately
designed the bundle's own signature as this feature's security control. Inventing a step-up flow
the backend doesn't support would misrepresent the actual authorization model.

### Build a separate "verify an uploaded bundle" page instead of folding verify into the export panel

Rejected as unnecessary scope for this issue: the acceptance criteria call for demonstrating that
verification works, and round-tripping the bundle the operator just exported satisfies that
without a new upload/paste surface. Nothing prevents extending the panel with a paste-to-verify
mode later if an operator workflow needs to check a bundle received out-of-band.

## Consequences

### Positive

- the fifth console mutation ships with the same session/CSRF guarantees as the four before it,
  while correctly reflecting that one of its two routes is actually a read;
- this ADR documents, for the first time, that a single feature's BFF routes can be split between
  `requireOperatorSession` and `resolveOperatorToken` when their backend classifications genuinely
  differ, rather than forcing uniform treatment across a feature;
- no backend PR was required;
- an operator can prove a bundle's integrity from within the same flow that produced it, without
  leaving the console.

### Negative

- no client-side role-based hiding, matching every prior mutation's precedent -- an unauthorized
  operator briefly sees the "Export evidence" action before the backend's own role check rejects
  the export attempt;
- the in-panel "Verify bundle" step only demonstrates a self-round-trip; verifying a bundle
  received from someone else still requires a future paste/upload extension not built here.

## Guardrails

- `evidence-export.ts` must continue to call `requireOperatorSession`, never `resolveOperatorToken`;
- `evidence-verify.ts` must continue to call `resolveOperatorToken`, matching `decision-replay.ts`'s
  classification, and must remain provably free of any write to backend state;
- `architecture:check` must continue to pass with `src/features/decisions/` outside
  `readOnlyScopes` and with no stale ARCH007 exceptions referencing it.

## Revisit criteria

Revisit if: the backend ever adds a step-up requirement to either endpoint; a future need arises
to verify a bundle the operator did not just export (would need a paste/upload UI, not a BFF
classification change); or a future mutation candidate needs the same read/mutation split within
one feature, in which case this ADR's precedent should be referenced rather than re-litigated.

## References

- Issue #214 -- Implement operator evidence export through the BFF.
- Issue #42 / ADR 0028 -- the backend evidence bundle service this issue exposes through the
  console, including its threat model and the rationale for why signing (not step-up) is this
  feature's security control.
- Issue #203 / ADR 0020 -- the immediately preceding mutation and the no-step-up criterion this
  ADR applies.
- Issue #194 / ADR 0018 -- the origin of the `requireOperatorSession`-only rule for mutations.
- `frontend/src/server/bff/evidence-export-core.ts`, `evidence-export.ts`,
  `evidence-export-fixtures.ts`, `evidence-verify-core.ts`, `evidence-verify.ts`,
  `evidence-verify-fixtures.ts`, `frontend/src/features/decisions/evidence-export-browser.ts`,
  `evidence-export-panel.tsx`.
