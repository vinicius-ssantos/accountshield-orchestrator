# ADR 0012: Pseudonymous subject tokens for integration events and a second retention job

- Status: Accepted
- Date: 2026-07-24

## Context

Issue #32 asked for data classification, pseudonymization, and retention policies across the platform. Investigation found that `OutboxEventRecorder` (`outbox/internal`) is the only place a domain event's full payload leaves the in-process boundary: it serializes each listened-to event verbatim into `outbox.outbox_event.payload`, the JSONB column that represents the genuine "integration event" per ADR 0009's outbox-relay design. Three of the four event types it records — `ProtectionDecisionMade`, `ChallengeCompleted`, `RecoveryCompleted` — carry a raw `accountReference` field, so the outbox currently persists raw account identifiers into what is meant to be an external-facing integration record.

Separately, only one table (`recovery.recovery_flow`, since issue #18) had an automated retention job; `challenge.challenge_plan` accumulates terminal challenge rows indefinitely with no cleanup.

This adopts a new cryptographic mechanism (deterministic account pseudonymization, distinct in purpose from the one-way `HmacChallengeCodeHasher` verification hash introduced in issue #20) and extends the data-retention strategy to a second table, which per this repo's ADR policy ("adopts a new cryptographic or data-retention strategy") requires a record here rather than a silent code change.

## Decision

### Pseudonymous subject token

Introduce `AccountPseudonymizer` (`outbox/internal`), a keyed HMAC-SHA256 function producing a deterministic, non-reversible hex token per account reference (`accountshield.privacy.pseudonym-secret`, matching `HmacChallengeCodeHasher`'s local-default-secret pattern from issue #20). `OutboxEventRecorder` computes this token and substitutes it for `accountReference` (as `subjectToken`) in the payload for the three affected event types before persisting. Determinism means downstream consumers can still correlate multiple events for the same subject without ever handling the raw identifier.

This is scoped to the outbox boundary only. In-process domain events (JVM-local, never persisted or logged with the raw reference by `SecurityEventLogger`) are unaffected — pseudonymizing them would add cost without closing a real leak, since they never cross a trust boundary.

### Second retention job

Add `ChallengePlanRetentionCleanup` (`challenge/internal`), mirroring `RecoveryFlowRetentionCleanup`'s shape: a `@Scheduled` job purging terminal challenge rows (`VERIFIED`, `CONSUMED`, `FAILED`, `EXPIRED`, via the new `ChallengeStatus.isTerminal()`) whose `expires_at` is older than a configurable TTL (`accountshield.challenge.retention.terminal-ttl`, default 1 day). `challenge_plan` has no `updated_at` column, so `expires_at` is the retention anchor — safe because `ChallengeApplicationService.consume()` already rejects any attempt to use a challenge past its `expires_at`, so a terminal row past that point can never be usefully acted on again.

## Alternatives considered

- **Encrypting/removing `accountReference` on the domain events themselves** — rejected as broader than necessary; it would touch every event's shape and every in-process listener, when only the outbox actually crosses a trust boundary today.
- **A reversible pseudonym (e.g. AES with a lookup table)** — rejected; deterministic HMAC gives correlation without needing to manage a reverse-mapping store, and nothing in the current design requires de-anonymization from the outbox payload.
- **A single generic "outbox-safe DTO" per event type** — rejected in favor of map-based field substitution in `OutboxEventRecorder`; per-event DTOs would duplicate every field and require updating in lockstep with each event's shape.

## Consequences

### Positive

- raw account identifiers no longer cross the integration-event boundary;
- the same account still correlates across outbox entries via a stable `subjectToken`;
- a second table now has automated, bounded, observable retention, following the same pattern as issue #18;
- both mechanisms reuse established patterns (`HmacChallengeCodeHasher`, `RecoveryFlowRetentionCleanup`) rather than introducing new infrastructure.

### Negative

- the pseudonym secret is a new operational secret to manage (mirrors the existing challenge HMAC secret's operational profile);
- `subjectToken` is not reversible, so any future need to look up the original account from an outbox entry alone would require a separate, deliberate mechanism;
- `PolicyActivated` and any future outbox-recorded event carrying an account reference must remember to route through the same redaction step — this is not yet enforced structurally.

## Guardrails

- `AccountPseudonymizer` is package-private to `outbox/internal`; nothing outside that boundary can call it directly;
- the redaction happens inside `OutboxEventRecorder.pseudonymizedPayload`, immediately before serialization — there is no path from a listened-to event to a persisted payload that skips it, for the three event types that carry an account reference;
- `ChallengePlanRetentionCleanup` only ever deletes rows whose status is already terminal and whose `expires_at` has passed by a full TTL window, mirroring the safety margin used by `RecoveryFlowRetentionCleanup`.

## Migration/compatibility implications

No schema migration is required: `AccountPseudonymizer` operates on payload serialization, not persisted columns, and `ChallengePlanRetentionCleanup` reuses the existing `expires_at` column. Existing rows are unaffected until they age past the retention TTL.

## Revisit criteria

This decision should be revisited when:

- a new outbox-recorded event carries an account reference (or other sensitive identifier) and needs the same redaction;
- a legitimate need arises to reverse a `subjectToken` back to its account reference (would require a deliberate, audited lookup mechanism, not a change to this HMAC scheme);
- `challenge_plan` retention needs a different anchor than `expires_at` (e.g. if a future feature requires challenges to remain queryable past their natural expiry).

## Links

- Issue #32
- [Data classification](../architecture/README.md#data-classification)
- [ADR 0004](0004-challenge-orchestration-via-simulated-providers.md) (established the `HmacChallengeCodeHasher` pattern this reuses)
- [ADR 0009](0009-outbox-relay-with-simulated-publisher.md) (defines the outbox as the integration-event boundary)
- Tests: `AccountPseudonymizerTest`, `OutboxEventIntegrationTest`, `ChallengePlanRetentionCleanupTest`
