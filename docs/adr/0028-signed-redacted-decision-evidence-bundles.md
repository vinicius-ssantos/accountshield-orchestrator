# ADR 0028: Signed and redacted decision evidence bundles

- Status: Accepted
- Date: 2026-07-27

## Context

Issue #42 asked for a reproducible, verifiable, privacy-preserving evidence package for a single
historical decision: decision metadata, normalized input, risk reasons, policy version, replay
result, an audit-chain proof, and a manifest with hashes and schema versions -- signed, with raw
sensitive identifiers absent by default, and with the export itself audited (who, why).

Investigation found the building blocks already exist and are already side-effect-free (ADR 0006):
`SimulationService.replay(protectionRequestId)` returns a complete, ready-to-embed `ReplayResult`
for any historical decision, and `DecisionTraceQuery.findByProtectionRequestId` returns the
decision's own metadata, normalized input, and reasons. Two things were missing: a way to fetch one
specific decision's own chain linkage (ADR 0027's `AuditChainVerificationService` only exposes
range verification and the chain tip), and any precedent for asymmetric, third-party-verifiable
signing -- every existing signer in this codebase (`HmacChallengeCodeHasher`,
`AccountPseudonymizer`, `webhook.internal.WebhookSigner`, `AuditChainHasher`) is symmetric HMAC,
verifiable only by someone who already holds the same secret.

## Decision

### New `evidence` module, composing three existing modules read-only

A new Spring Modulith module (`io.github.viniciusssantos.accountshield.evidence`) with no
dependents of its own. `EvidenceBundleApplicationService` composes `DecisionTraceQuery`,
`SimulationService.replay`, `AuditChainVerificationService.findProof` (new), and `outbox
.AccountPseudonymizer` (reused, not reimplemented) -- all pure reads. It performs exactly one
write of its own: an append-only row in the new `audit.evidence_export_log` table recording who
exported the bundle and why. Nothing in `protection`, `challenge`, `recovery`, `policy`, or
`audit.decision_trace`/`decision_reason` is ever touched, so export inherits ADR 0006's
side-effect-free guarantee by construction.

### Per-decision chain proof: `AuditChainVerificationService.findProof(UUID decisionId)`

ADR 0027 only exposed range verification and the current tip. This adds a single-row lookup
(`AuditChainRecordProof`: `chainSequence`, `previousHash`, `recordHash`, `hashAlgorithm`,
`canonicalSchemaVersion`) by `decision_trace.id`, filtered on `chain_sequence IS NOT NULL` the same
way ADR 0027's own "tip" query is, so a decision recorded before the hash chain existed correctly
yields no proof rather than a row with meaningless nulls.

### Canonical content: fixed record field order plus a sorted map, not `DataOutputStream`

Every prior hasher in this codebase (`RequestFingerprint`, `AuditChainHasher`) canonicalizes a
*flat, fixed set* of fields via hand-rolled `DataOutputStream` writes. An evidence bundle's content
is a *nested document* with a variable-shaped `normalizedContext` map (whatever signal fields a
given decision recorded) plus embedded records (`ReplayResult`, `AuditChainRecordProof`) -- there
is no fixed flat field list to write in order. Extending the `DataOutputStream` pattern to an
arbitrary nested document would mean hand-writing a serializer for a shape that already has one
(Jackson). Instead, `EvidenceBundleContent`'s own field order is fixed by its record declaration
(Jackson serializes records in canonical-constructor order), and its `normalizedContext` field is
coerced to a `SortedMap` in the compact constructor -- so two exports of the same historical
decision always serialize to byte-identical canonical JSON regardless of map insertion order. The
finish is unchanged from every other hasher: SHA-256 over the resulting UTF-8 bytes, hex-encoded.

### Signing: a new, separate per-boot RSA signer, not `LocalJwtKeys`

`LocalJwtKeys` generates a per-boot RSA keypair but is tightly coupled to signing JWT claims sets
(`SignedJWT`) and exposes no getter for the keypair. Rather than retrofit it into a generic
byte-signer, `EvidenceBundleSigner` is a new, separate component: a per-boot (never persisted)
RSA keypair, `java.security.Signature` with `SHA256withRSA` (zero new dependencies -- plain JDK,
matching this codebase's general preference over pulling in the Nimbus classes already on the
classpath transitively). The key size is configurable
(`accountshield.evidence.signing.key-size`, default 2048) as the issue's "configurable ... signer"
asked for. Unlike every existing signer, this one is asymmetric: `EvidenceManifest` embeds the
signer's public key (base64 X.509) alongside the signature, so a bundle is independently verifiable
by anyone holding only the bundle -- no access to the issuing system is required. This is
explicitly a **local/demo signer**, the same posture `LocalJwtKeys` documents for itself: the key
is regenerated every boot and never persisted, so a signature from one running instance cannot be
verified after that instance restarts using a different bundle's embedded key (it can still be
verified using that bundle's own embedded key -- see Limitations).

### Redaction: reuse `AccountPseudonymizer`, don't reinvent it

`decision_trace.account_reference` is the one raw sensitive identifier in the decision's own
record; `request_fingerprint` is already a hash (ADR 0003) and `normalizedContext` was confirmed
(by reading `ProtectionDecisionApplicationService.normalizedContext`) to never embed the raw
account reference among its signal fields. The bundle replaces the raw account reference with
`outbox.AccountPseudonymizer.pseudonymize(...)` -- the exact same HMAC-based pseudonymization
already used at the outbox integration boundary (ADR 0012), reused rather than duplicated, so this
issue does not introduce a second redaction scheme with potentially different guarantees.

### Manifest and audit trail

`EvidenceManifest` carries `bundleSchemaVersion`, `decisionId`, `protectionRequestId`,
`generatedAt`, `exportedBy` (from `Authentication.getName()`, matching
`OutboxAdminController.requeue`'s existing actor-extraction precedent -- not client-supplied, since
the caller should not be able to misattribute who exported evidence), `exportReason`
(client-supplied, validated 1-500 characters, mirroring `RecoveryReviewCommand`'s reviewer-field
validation), `contentHash`/`contentHashAlgorithm`, and `signature`/`signatureAlgorithm`/
`signingPublicKey`. The same export also inserts one row into `audit.evidence_export_log`
(decision id, protection request id, actor, reason, content hash, timestamp) -- a separate
append-only log, not folded into `decision_trace`'s own hash chain, since an export is a read-only
act over already-recorded evidence, not a new decision event; conflating the two would mix two
different kinds of history in one sequence.

### Endpoints, not a CLI

`POST /api/v1/evidence/export` and `POST /api/v1/evidence/verify`, both `SECURITY_OPERATOR`-gated
like every other operator-facing endpoint in this codebase (`/api/v1/audit/**`,
`/api/v1/outbox/**`, `/api/v1/webhooks/**`). No existing controller in this codebase returns a raw
byte stream or `Content-Disposition` download (confirmed by search); every one returns a JSON DTO.
A bundle is returned the same way -- plain JSON -- satisfying "verification CLI or endpoint" via
the endpoint half of that either/or, and matching this codebase's established convention instead of
introducing a new download-response pattern with no precedent to follow. `verify` is
self-contained: it recomputes the content hash and checks the signature using the *bundle's own*
embedded public key, requiring no database access and no dependency on which instance issued it.

## Threat model and limitations

**What this catches:** any modification to the bundle's content after export (the recomputed
content hash and/or the signature check fails), and any attempt to redistribute a bundle claiming a
different actor/reason than what was actually recorded (the manifest's `exportedBy`/`exportReason`
are part of what gets checked against the durable `audit.evidence_export_log` row, though `verify`
itself only checks internal bundle self-consistency, not cross-referencing the log -- see below).

**What this does not catch:** `verify` proves the bundle is internally self-consistent (content
matches its declared hash, and the signature over that content is valid for the embedded public
key) -- it does **not** independently confirm the embedded public key actually belongs to this
system's real signer, since the key travels inside the bundle itself. A forger with no access to
any real signing key could still generate their *own* keypair, sign a fabricated bundle with it,
and pass `verify`. Closing this fully requires an out-of-band channel for distributing/pinning the
signer's real public key (e.g. published alongside the root-hash anchor ADR 0027 already exposes as
a hook) -- explicitly out of scope here, matching ADR 0027's own root-hash-anchoring deferral.

**Ephemeral signing key:** the signer's keypair is regenerated every boot and never persisted, the
same posture as `LocalJwtKeys`. This is adequate for the local/demo scope of this issue; a
production deployment needing bundles verifiable across restarts, or by a party who received the
bundle long after export, would need a persisted (and properly access-controlled) signing key --
explicitly deferred, matching this codebase's existing "local/demo" framing for `LocalJwtKeys`.

**Chain proof absence:** a decision recorded before ADR 0027's migration has no chain columns, so
its bundle's `chainProof` is `null` -- documented as evidence that predates chain coverage, not a
defect.

## Alternatives considered

- **Extending the `DataOutputStream` canonical-hash pattern to the whole bundle** -- rejected: that
  pattern exists specifically for a fixed, flat field list; a nested, variable-shaped document
  (arbitrary `normalizedContext` keys, embedded records) has no such list to hand-write in order.
  Fixed record-declaration order plus a sorted map achieves the same determinism without inventing
  a bespoke serializer for a shape Jackson already serializes deterministically.
- **A real zip/tar archive bundle** -- rejected: `pom.xml` has no archive library, `java.util.zip`
  is unused anywhere in this codebase today, and every existing persistence/API boundary in this
  codebase is JSON. A single canonical JSON document matches established convention with zero new
  machinery.
- **Reusing `LocalJwtKeys`'s keypair for signing** -- rejected: it is tightly coupled to JWT claims
  sets and exposes no accessor for its keypair; retrofitting it would couple two unrelated concerns
  (issuing bearer tokens vs. signing evidence bundles) for no benefit over a small, separate signer.
- **A dedicated CLI binary for verification** -- rejected: the issue's acceptance criterion is
  "CLI or endpoint"; a stateless, self-contained `POST /api/v1/evidence/verify` endpoint (no DB
  access needed) satisfies the same need without introducing a new build artifact and packaging
  concern this codebase has no precedent for.
- **Extracting `actor` from the request body instead of `Authentication`** -- rejected: a
  client-supplied "who exported this" would let any caller misattribute the action to someone
  else; deriving it from the authenticated principal (already the pattern in
  `OutboxAdminController.requeue`) makes the audit log trustworthy.

## Consequences

### Positive

- a decision's full evidentiary context (metadata, normalized input, reasons, replay outcome,
  chain proof) is now exportable as one canonical, hashed, signed, independently verifiable
  document, satisfying every acceptance criterion in issue #42;
- redaction reuses an existing, already-reviewed pseudonymization scheme rather than adding a
  second one with potentially different guarantees;
- export is provably side-effect-free by construction (composes only existing read paths plus one
  new append-only audit insert);
- who exported a bundle and why is durably recorded, independent of the bundle itself.

### Negative

- the signer's ephemeral, per-boot key means bundles are only verifiable relative to their own
  embedded key, not against a stable, independently-trusted identity -- a real deployment needing
  that would require a persisted key and a key-distribution story this issue does not build;
- `verify` cannot detect a bundle forged end-to-end with an attacker's own keypair, only tampering
  with a bundle whose embedded key the verifier already trusts through some other channel.

## Guardrails

- `EvidenceBundleSignerTest` proves sign/verify round-trips against the signer's own key, and fails
  against tampered content, a different signer's key, or garbage input (never throwing);
- `EvidenceExportControllerTest` proves the actor comes from the authenticated principal (not the
  request body), 404 on an unknown protection request, 400 on an invalid reason, and that `verify`
  delegates correctly;
- `EvidenceBundleIntegrationTest` (real Postgres, real `decide()` + replay + chain proof) proves:
  an exported bundle verifies clean; the raw account reference never appears anywhere in the
  bundle; two exports of the same historical decision by different actors/reasons produce
  byte-identical canonical content and content hash; a tampered bundle fails verification; every
  export is recorded in `audit.evidence_export_log`; exporting a nonexistent protection request
  returns empty.

## Migration/compatibility implications

`V24__add_evidence_export_log.sql` adds `audit.evidence_export_log`, a foreign key to
`audit.decision_trace(id)`, and reuses the existing `audit.reject_audit_mutation()` trigger
function (ADR 0020/V1) so the log is append-only at the database level, not just by convention.
`accountshield_runtime` receives the same `SELECT`/`INSERT`-only posture every other audit-schema
table already gets from ADR 0024's default-privilege rule; no new grant statements are needed.

## Revisit criteria

- when a production deployment needs bundles verifiable across application restarts or long after
  export, requiring a persisted, access-controlled signing key and a real key-distribution
  mechanism;
- when an external, independently-trusted channel for the signer's public key (or the ADR
  0027 root-hash anchor) is built, closing the "forged-from-scratch bundle" gap noted above;
- if a real archive format (multiple files, attachments) is ever needed instead of one JSON
  document.

## Links

- Issue #42
- [ADR 0006](0006-deterministic-replay-and-shadow-evaluation.md) (side-effect-free replay, relied
  on unchanged)
- [ADR 0012](0012-pseudonymous-subject-tokens-for-integration-events.md) (the pseudonymization
  scheme reused for redaction)
- [ADR 0020](0020-replay-provenance-canonical-hash-and-catalog-versions.md) (the canonical-hash/
  SHA-256/hex-encoding finish reused for the bundle's content hash)
- [ADR 0024](0024-database-least-privilege-and-integrity.md) (the grant pattern the new export-log
  table inherits)
- [ADR 0027](0027-tamper-evident-audit-hash-chaining.md) (the chain this issue's proof reads from,
  and the root-hash-anchoring deferral this issue's signing-key limitation mirrors)
- Tests: `EvidenceBundleSignerTest`, `EvidenceExportControllerTest`, `EvidenceBundleIntegrationTest`
