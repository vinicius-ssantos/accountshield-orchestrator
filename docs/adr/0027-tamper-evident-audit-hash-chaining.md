# ADR 0027: Tamper-evident hash chaining for decision audit records

- Status: Accepted
- Date: 2026-07-26

## Context

Issue #40 asked for unauthorized historical modification of `audit.decision_trace` to be
detectable "beyond update/delete database triggers." `audit.reject_audit_mutation()` (issue #20)
already unconditionally blocks every `UPDATE`/`DELETE` on `decision_trace` and `decision_reason`
via a `BEFORE` trigger -- but a trigger only stops *ordinary* application-level DML. It does
nothing against someone with elevated database access (a compromised or malicious superuser
temporarily disabling the trigger, or direct storage-level tampering) -- exactly the gap this
issue closes.

Investigation found no existing gap-free ordering column anywhere in the schema:
`decision_trace.id` is a random `UUID`, `decided_at` has no uniqueness guarantee, and grepping
every migration for `SERIAL`/`GENERATED ALWAYS AS IDENTITY` found zero matches in this codebase.
A real hash chain needs an unambiguous, complete ordering, so this migration introduces the first
one. `protection.RequestFingerprint` (ADR 0020) already established this codebase's canonical-
hash pattern -- hand-rolled fixed-field-order `DataOutputStream` writes, SHA-256, hex-encoded --
specifically so a write-time computation and a later verification recomputation can never drift.
This issue reuses that exact pattern rather than introducing a JSON-canonicalization library.

`audit.decision_reason` rows are 1-to-many children of a `decision_trace` row, written in the
same transaction; nothing in the codebase treats them as independently significant evidence.

Separately, issue #47 (ADR 0026) explicitly deferred an `audit.integrity.failed` webhook event
because a real integrity-verification mechanism "would require inventing a periodic checksum/
hash-chain verification job -- a separate, materially larger feature." This issue is that job;
wiring the event in now closes that deferral.

## Decision

### One chain over `decision_trace`, not two

A single hash chain over `decision_trace` rows. Each row's canonical payload
(`AuditChainHasher.computeRecordHash`) includes its `decision_reason` children (code and
contribution, in `ordinal` order), so tampering with a reason changes its parent trace's hash --
detected by the same mechanism, without a second independent chain and a second sequence column.

### `chain_sequence` is application-assigned, not a database identity

`V23` adds `chain_sequence BIGINT UNIQUE` (not `SERIAL`/`IDENTITY`), plus `previous_hash`,
`record_hash`, `hash_algorithm`, and `canonical_schema_version`. `JdbcDecisionTraceRecorder`
acquires a fixed-key Postgres advisory transaction lock
(`pg_advisory_xact_lock`, released automatically at commit/rollback) before reading the current
last link (`ORDER BY chain_sequence DESC LIMIT 1`), computing `chain_sequence = last + 1`, and
computing `record_hash` from that sequence, the previous row's hash, and the row's own content.
This serializes all concurrent decisions into one unambiguous chain -- including when the chain
is currently empty, where a row-level lock on "the last row" would have nothing to lock against
(a real gap a naive `SELECT ... FOR UPDATE` approach would have left open). A side effect worth
naming: because `chain_sequence` is computed from the actually-committed last row rather than an
independent counter, it is guaranteed gap-free under normal operation (unlike a `SERIAL`, which
can skip values on rollback) -- so an observed gap during verification is itself a genuine
tamper signal (a physically deleted row), not noise to filter out.

### Verification: bounded ranges, forward-only checkpoint

`AuditChainVerificationService.verifyRange(from, to)` recomputes every row's hash in the range
and checks two things per row: its `chain_sequence` is exactly one more than the previous row's
(catches gaps -- i.e. deletions), and its `previous_hash` equals the previous row's actual
`record_hash` (catches relinking). It also fetches the hash of the row immediately before the
range so the *first* row in a bounded scan still gets its linkage checked, not just rows entirely
inside the window.

`AuditChainIntegrityCheckJob` advances a single-row checkpoint (`audit
.chain_verification_checkpoint`) forward through history in bounded batches
(`accountshield.audit.chain.verification.batch-size`, default 500), matching this codebase's
retention-job shape (`@Scheduled @Transactional`, `@Qualifier("decisionClock") Clock`). On a
detected break, the checkpoint is **deliberately not advanced past it** -- the break stays
flagged on every subsequent tick until an operator investigates, rather than silently marching
past corrupted history. This makes the design forward-only: once a range verifies clean and the
checkpoint passes it, that range is not re-checked later. See Limitations.

### Alerts without mutating evidence

A break increments `accountshield.audit.chain.verified{outcome=broken}` (a matching `outcome=valid`
counter records normal ticks), logs at `ERROR`, and publishes `AuditChainIntegrityFailed` --
routed through the existing outbox (`OutboxEventRecorder`'s fifth recorded event type,
`AUDIT_INTEGRITY_FAILED`) and, from there, to any active webhook subscription (ADR 0026),
completing that ADR's deferral. None of this touches `decision_trace` or `decision_reason` --
only the checkpoint (operational bookkeeping, not evidence) and the outbox.

### Operator diagnostics

`GET /api/v1/audit/chain/verify?from=&to=` (on-demand verification of an arbitrary range) and
`GET /api/v1/audit/chain/root-hash` (the current chain tip), both `SECURITY_OPERATOR`-gated,
matching `OutboxAdminController`'s existing pattern. The root-hash endpoint is this issue's
"periodic root-hash evidence" hook: an operator or external system can poll it and record the
value somewhere genuinely outside this application's control (see Limitations for why that
external step matters and is not built here).

## Threat model and limitations

**What this catches:** any row content change, any row deletion, and any row reordering that
happens *without* also recomputing every downstream `record_hash`/`previous_hash` to stay
internally consistent -- which is what an ordinary compromised-credential or accidental-mutation
scenario looks like, and exactly what bypassing (not exploiting a flaw in) the append-only
trigger would produce.

**Coverage boundary -- `normalized_context` is not in the chain hash.** `AuditChainHasher`
includes the decision's identity, request fingerprint, algorithm/policy versions, outcome,
risk score, timestamp, and reason children -- but **not** the `normalized_context` JSONB column.
This is a deliberate boundary, now stated explicitly rather than left implicit: the chain alone
cannot detect tampering with *which signals fed the decision*. It is covered by defense in depth
instead:

- `audit.decision_trace` is append-only at the database level (`trg_decision_trace_append_only`
  blocks `UPDATE`/`DELETE` on the whole row), so the easy mutation path is closed.
- `outcome` and `risk_score` **are** in the hash, and replay (ADR 0019/0020) recomputes the
  score from `normalized_context` -- so an attacker who rewrites the signals cannot adjust the
  outcome/score to stay consistent with the chain without breaking it.
- evidence bundles (ADR 0028) include `normalizedContext` in their signed content, giving an
  independently verifiable snapshot of the exact signals at decision time.

A future change that folds `normalized_context` into the chain hash would require bumping
`CANONICAL_SCHEMA_VERSION` (already stored per row, so old history keeps verifying under the old
version). Until then, the chain's tamper-evidence guarantee applies to every field listed in
`AuditChainHasher.computeRecordHash` and to `normalized_context` only via the mechanisms above.

**What this does not catch:** an attacker with sustained, privileged write access who tampers
with a row *and* recomputes every subsequent row's hash to keep the chain internally consistent.
A hash chain stored in the same database it protects cannot distinguish a self-consistent forged
chain from a genuine one -- only an independent, external anchor (the root hash recorded
somewhere the attacker does not also control) can catch that. This issue exposes the root-hash
endpoint as the hook for such an anchor; it does not build the external anchoring mechanism
itself, which was explicitly out of scope ("no blockchain or distributed ledger is required").

**Forward-only verification:** once the checkpoint passes a range, it is not re-verified later.
An attacker who tampers with already-checkpointed history (and keeps the chain internally
consistent from that point forward) will not be caught by the scheduled job, though it remains
detectable via `verify` against an explicit historical range on demand.

**Pre-migration rows:** rows written before `V23` have `NULL` chain columns and are outside what
this feature can verify -- they remain evidence (append-only, per the existing trigger), just not
chain-covered.

## Alternatives considered

- **A database `SERIAL`/`IDENTITY` sequence for `chain_sequence`** -- rejected: an identity
  column's value is assigned before the row's content-dependent hash can be computed, and using
  `RETURNING` after insert would mean issuing a second `UPDATE` to add the hash -- forbidden by
  the append-only trigger. Application-assigned sequencing, computed together with the hash
  before a single `INSERT`, avoids this entirely.
- **Row-level `SELECT ... FOR UPDATE` on the last row instead of an advisory lock** -- rejected:
  when the chain is empty there is no row to lock, leaving a real race window for the first
  insert. An advisory lock serializes correctly regardless of whether the table is empty.
- **A second independent chain over `decision_reason`** -- rejected as unnecessary; folding
  reasons into the parent trace's canonical payload detects reason tampering with one mechanism.
- **Continuous re-verification of all history on every tick** -- rejected as unbounded cost
  against a table retained indefinitely (ADR 0024); a forward-only checkpoint bounds the
  scheduled job's cost while still leaving on-demand full-range verification available.

## Consequences

### Positive

- historical tampering that bypasses the append-only trigger (the actual gap this issue closes)
  is now detectable, not just ordinary-DML tampering;
- write-time and verify-time hashing share one implementation (`AuditChainHasher`), so they
  cannot silently drift, mirroring ADR 0020's established discipline;
- `audit.integrity.failed` (deferred in ADR 0026) now has a real, natural trigger point;
- zero new scheduled-job infrastructure patterns -- this reuses the existing retention-job shape.

### Negative

- every `decision_trace` insert now takes a global advisory lock, serializing all protection
  decisions' audit writes against each other -- an accepted throughput ceiling for this system's
  scale, not appropriate to carry forward unmodified into a high-throughput deployment;
- the chain cannot, by itself, defend against a sufficiently privileged and sustained attacker
  (see Limitations) -- root-hash export closes that gap only if paired with an external anchor
  this issue does not build;
- forward-only verification means already-checkpointed history is not continuously re-watched.

## Guardrails

- `AuditChainHasherTest` proves the hash is deterministic, sensitive to every input including the
  reason list, and rejects an unrecognized canonical schema version;
- `AuditChainIntegrationTest` proves consecutive real decisions form a verifiable chain against
  real Postgres;
- `AuditChainVerificationServiceTest` proves: a valid chain verifies clean; a tampered
  `record_hash` is detected; a broken `previous_hash` link is detected; a row physically deleted
  by temporarily disabling the append-only trigger (simulating the elevated-access threat model)
  is detected; a row with an unknown `canonical_schema_version` is detected;
- `AuditChainIntegrityCheckJobTest` proves the checkpoint advances on a valid batch, does not
  advance past a break, and publishes `AuditChainIntegrityFailed` exactly on a break.

## Migration/compatibility implications

`V23__add_audit_hash_chain.sql` adds five nullable columns to `audit.decision_trace` (nullable so
existing pre-migration rows do not need a backfill) plus a `UNIQUE` constraint on
`chain_sequence`, and creates the single-row `audit.chain_verification_checkpoint` table, granting
it the same least-privilege pattern ADR 0024 established (`accountshield_runtime` gets `UPDATE`
in addition to the `SELECT`/`INSERT` it already receives from that ADR's default-privilege rule,
since this table -- unlike `decision_trace` -- is mutable operational bookkeeping).

## Revisit criteria

- when a real external root-hash anchoring mechanism is needed (e.g. publishing to a separate
  system this application does not control);
- if `decision_trace` write throughput grows enough that the global advisory lock becomes a
  bottleneck, requiring a less strictly serialized chaining strategy;
- if periodic re-verification of already-checkpointed history becomes a real requirement.

## Links

- Issue #40
- [ADR 0020](0020-replay-provenance-canonical-hash-and-catalog-versions.md) (the canonical-hash
  pattern this ADR reuses)
- [ADR 0024](0024-database-least-privilege-and-integrity.md) (the grant pattern extended to the
  new checkpoint table)
- [ADR 0026](0026-signed-webhook-delivery-with-replay-protection.md) (deferred `audit.integrity
  .failed`, completed by this ADR)
- Tests: `AuditChainHasherTest`, `AuditChainIntegrationTest`, `AuditChainVerificationServiceTest`,
  `AuditChainIntegrityCheckJobTest`, `AuditChainControllerTest`
