# ADR 0025: Envelope encryption, key rotation, and crypto-shredding for `account_reference`

- Status: Accepted
- Date: 2026-07-26

## Context

Issue #49 asked for encryption-at-rest of sensitive identifiers with key rotation and controlled
cryptographic deletion. Investigation found that every existing "encryption" mechanism in this
codebase (`AccountPseudonymizer` from ADR 0012, `HmacChallengeCodeHasher` from issue #20) is a
one-way keyed HMAC: neither can be decrypted back, and neither has any notion of key versioning.
Nothing in the codebase does reversible encryption.

`account_reference` is stored in plaintext, `VARCHAR(128)`, in five tables:
`protection.protection_request`, `audit.decision_trace`, `challenge.challenge_plan`,
`recovery.recovery_flow`, and `recovery.recovery_authorization`. Grepping every repository found
no JPA query method that filters by `account_reference` in any of them, so encrypting the column
does not break query behavior anywhere in the current codebase.

`audit.decision_trace.account_reference` specifically is also read back as plaintext by two live
code paths outside the audit module: `SimulationApplicationService` (deterministic replay) and
`PolicyImpactAnalysisApplicationService` (which re-pseudonymizes it for impact-analysis
reporting). Encrypting that column correctly would also require `DecisionTraceView`'s producing
query to decrypt on read and both consumers to keep working against the resulting plaintext --
a second, separable unit of work. `challenge.challenge_plan`, `recovery.recovery_flow`, and
`recovery.recovery_authorization` were not investigated at the same depth for a green-field
change of this size.

## Decision

### Scope: `protection.protection_request.account_reference` only

This issue encrypts `account_reference` at rest in `protection.protection_request`, the source
of truth per ADR 0002 and the only one of the five tables written and never read back as
plaintext by any other module. **Explicitly deferred**: applying the same mechanism to
`audit.decision_trace`, `challenge.challenge_plan`, `recovery.recovery_flow`, and
`recovery.recovery_authorization` is follow-up work once this pattern is proven. Until that
follow-up lands, crypto-shredding a subject under this ADR makes their identifier irrecoverable
in `protection_request` only -- the same plaintext value still exists in the other four tables.
This is a real, deliberate limit on the "irrecoverable" guarantee, not an oversight: extending it
to `decision_trace` requires updating the two read paths above; extending it to the challenge/
recovery tables is untouched, larger, separate work.

### Key hierarchy

A two-level envelope:

- **Key-encryption key (KEK)**: an AES-256 key whose material is provided as base64-encoded
  bytes that must decode to exactly 32 bytes, validated at construction. This rejects
  human-readable passphrases -- a bare SHA-256 of a passphrase offers no resistance to offline
  brute force, so the operator must supply real high-entropy key material (e.g.
  `openssl rand -base64 32`, documented in `docs/RELEASING.md`). Exactly one **active** version
  and, during a rotation window, one **previous** version are configured
  (`accountshield.crypto.active-kek-version`/`-secret`, `...previous-kek-version`/`-secret`) --
  enough to satisfy "support current and previous data-encryption keys" without an open-ended key
  ring, which this system has no present need for.
- **Data-encryption key (DEK)**: a random AES-256 key generated per **subject**, wrapped
  (AES-GCM) by the active KEK at creation time. The subject identifier is a deterministic,
  one-way HMAC-SHA256 of the plaintext account reference, using its own dedicated secret
  (`accountshield.crypto.subject-id-secret`) -- independent of `AccountPseudonymizer`'s secret, so
  the new `crypto` module has no dependency on `outbox` internals, keeping the Modulith boundary
  clean.

Each field value is encrypted with its subject's DEK, not the KEK directly: `ENC:` +
base64(subjectIdBytes + nonce + AES-GCM(plaintext)). Rotating the KEK re-wraps every subject's DEK
(`SubjectKeyRewrapJob`, see below) without ever touching or re-encrypting the field ciphertext
itself, so rewrap cost scales with the number of distinct subjects, not the number of rows that
reference them.

### `crypto.subject_key`

New table, new schema, one row per subject: `subject_id` (primary key, hex HMAC digest),
`wrapped_dek`, `dek_nonce`, `kek_version`, `created_at`, `rewrapped_at`, `destroyed_at`. A CHECK
constraint enforces that a row is either "live" (all three key fields present, `destroyed_at`
null) or "destroyed" (all three key fields null, `destroyed_at` set) -- never a mix. Rows are
never physically deleted; crypto-shredding nulls the key material in place, so the row itself
stays structurally valid (queryable, countable, join-able) even after the subject's data is
irrecoverable.

### `FieldEncryptionService` (public API of the new `crypto` module)

`encrypt`, `decrypt`, `shred`. `decrypt` returns a value unchanged if it does not carry the
`ENC:` prefix (a legacy plaintext row written before this feature, or before a given row's next
write) -- this is what makes "old records remain readable during migration" true without a
separate backfill job blocking reads. `decrypt` returns `FieldEncryptionService.SHREDDED_MARKER`
rather than throwing when the owning subject key is destroyed, so a bulk read over historical
data (e.g. a future report) stays resilient to individually shredded subjects instead of failing
the whole read. `encrypt` on an already-shredded subject throws `SubjectKeyDestroyedException` --
crypto-shredding is treated as a terminal action for that identifier, not something a later write
can quietly undo.

`account_reference` is wired transparently via a JPA `AttributeConverter`
(`AccountReferenceEncryptionConverter`, registered as a `@Component` so Spring Boot's Hibernate
bean container can inject `FieldEncryptionService` into it) on `ProtectionRequestEntity`. No
change was needed to `ProtectionDecisionApplicationService` itself.

### `SubjectKeyRewrapJob`

Follows this codebase's established retention-job shape (`@Component`, `@Qualifier("decisionClock")
Clock`, `@Value`-injected batch size and fixed delay, `@Scheduled @Transactional` method). Each
tick re-wraps a bounded batch of subject keys still on a non-active KEK version onto the active
one. A `Gauge` (`accountshield.crypto.rewrap.pending`) exposes the remaining count and a
`Counter` (`accountshield.crypto.rewrap.count`) the cumulative amount rewrapped -- the "rotation
progress" metrics the issue asked for. Rotating in practice: an operator sets the previous-KEK
config to today's active values, sets new active values, redeploys (config-only, no code
change); old subject keys stay decryptable via the previous slot throughout, and the job migrates
them in the background with zero downtime.

## Alternatives considered

- **A single global KEK encrypting every value directly (no per-subject DEK)** -- rejected: this
  is the only design that makes crypto-shredding a single subject possible without destroying
  every other subject's data. A global key can only be destroyed for everyone at once.
- **Encrypting all five `account_reference` columns in this issue** -- rejected as oversized for
  one issue given `decision_trace`'s live plaintext read paths (see Context); scoped down with an
  explicit deferral instead of silently declaring victory over a partial guarantee.
- **An open-ended `Map<Integer, String>` of KEK versions** -- rejected in favor of a fixed
  active/previous pair; the acceptance criteria only ever require "current and previous," and a
  fixed pair is simpler to configure, reason about, and test than an unbounded map.
- **A generic Google Tink/BouncyCastle dependency** -- rejected; plain JDK `javax.crypto`
  (`Cipher`, `AES/GCM/NoPadding`) is sufficient and matches this codebase's existing preference
  for JDK-native crypto (`Mac`, `MessageDigest`) over third-party libraries.
- **Deriving the KEK from a passphrase via a password-based KDF (PBKDF2/scrypt/Argon2)** --
  rejected in favor of requiring raw high-entropy key material. A KDF would still leave the
  system dependent on the operator choosing a strong passphrase, and requires persisting a salt
  per KEK version. Requiring 32 bytes of base64-encoded key material (validated at boot, blocked
  in production by `ProductionSecretsGuard`) is simpler and removes the passphrase weakness
  entirely.

## Consequences

### Positive

- `protection.protection_request.account_reference` is encrypted at rest with a real,
  per-subject, revocable key;
- crypto-shredding a subject is `O(1)` (null three columns on one row), not a data-rewriting
  operation, and it never touches or deletes the rows that reference the subject;
- KEK rotation re-wraps only `crypto.subject_key` rows, not every encrypted field row --
  cheap, bounded, and resumable/idempotent the same way this codebase's retention jobs already
  are (re-running a rewrap tick on an already-rewrapped row is a same-version no-op);
- the crypto module has no dependency on `outbox` or any other module's internals.

### Negative

- `audit.decision_trace`, `challenge.challenge_plan`, `recovery.recovery_flow`, and
  `recovery.recovery_authorization` still store `account_reference` in plaintext; crypto-shredding
  under this ADR is not yet a whole-system guarantee (see Scope above);
- application-level field encryption means `account_reference` can no longer be filtered or
  joined on at the SQL level -- confirmed acceptable because no existing query does so, but any
  future feature needing to query by account reference will need a different mechanism (e.g. a
  deterministic blind index), not a straightforward `WHERE` clause;
- three new operational secrets to manage (`active-kek-secret`, `previous-kek-secret`,
  `subject-id-secret`), on top of the existing challenge/pseudonym secrets;
- `ProtectionDecisionIntegrationTest` could no longer match `protection_request` rows by a literal
  `WHERE account_reference = ?` once the real `decide()` path started encrypting the column; its
  `requestCount` helper now decrypts each row in Java instead.

## Guardrails

- `crypto.subject_key`'s CHECK constraint makes "live XOR destroyed" a database-level invariant,
  not just an application convention;
- `AccountReferenceEncryptionConverter` is the only path that writes or reads
  `protection_request.account_reference`; no other code encrypts or decrypts it directly;
  `AesGcmFieldEncryptionServiceTest.decryptingAnEncryptedValueWhoseSubjectKeyRowIsMissingFailsLoudly`
  and `...encryptingAfterShredThrows` prove the failure modes are loud, not silent;
  `AccountReferenceEncryptionIntegrationTest` proves the round trip and the crypto-shredding
  guarantee end to end against real Postgres;
- `crypto.subject_key` inherits the exact same `accountshield_runtime`/`accountshield_readonly`
  least-privilege grant pattern ADR 0024 established for the other six schemas -- no ad hoc
  permission model for the new schema.

## Migration/compatibility implications

`V21__add_crypto_subject_keys_and_widen_account_reference.sql` creates the `crypto` schema and
`subject_key` table, widens `protection.protection_request.account_reference` to `VARCHAR(512)`
(ciphertext plus its subject-id/nonce header is wider than the plaintext it replaces), and extends
the ADR-0024 grants to the new schema. No other table is touched. Existing plaintext rows in
`protection_request` remain readable indefinitely without a backfill job, because `decrypt`
passes through any value that is not in the `ENC:` encrypted representation.

## Revisit criteria

- when `audit.decision_trace`'s two live plaintext read paths (`SimulationApplicationService`,
  `PolicyImpactAnalysisApplicationService`) are updated to decrypt on read, extend this mechanism
  to that column;
- when `challenge.challenge_plan`, `recovery.recovery_flow`, or `recovery.recovery_authorization`
  need the same guarantee;
- if a real production need for more than one "previous" KEK version at a time emerges (this
  design intentionally does not support it);
- if a future feature needs to query or join by account reference, requiring a deterministic
  blind-index column alongside the encrypted value.

## Links

- Issue #49
- [ADR 0012](0012-pseudonymous-subject-tokens-for-integration-events.md) (its own "alternatives
  considered" section anticipated and deferred exactly this encryption work)
- [ADR 0024](0024-database-least-privilege-and-integrity.md) (the least-privilege grant pattern
  this ADR's migration extends into the new `crypto` schema)
- [Data classification](../architecture/README.md#data-classification)
- Tests: `AesGcmFieldEncryptionServiceTest`, `SubjectKeyRewrapJobTest`,
  `AccountReferenceEncryptionIntegrationTest`
