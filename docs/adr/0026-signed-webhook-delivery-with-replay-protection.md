# ADR 0026: Signed webhook delivery with replay protection, backed by the transactional outbox

- Status: Accepted
- Date: 2026-07-26

## Context

Issue #47 asked for a secure external integration mechanism, explicitly "backed by the
transactional outbox." The outbox subsystem (ADR 0023) already provides everything a reliable
delivery mechanism needs: an explicit status machine, atomic `FOR UPDATE SKIP LOCKED` claiming,
bounded exponential backoff with jitter, a `maxAttempts`-bounded dead-letter path, and a
versioned integration-event envelope. Its only real gap for this issue is the publisher itself:
`LoggingOutboxEventPublisher` is a log-only stub, the sole `OutboxEventPublisher` implementation,
registered via `@ConditionalOnMissingBean` in `OutboxConfiguration` specifically so a real
implementation can replace it without touching the outbox module at all.

Two scope findings from investigation:

- **Retries already reuse the same logical ID.** `OutboxRelay.dispatchSingle` never creates a
  new outbox row on failure -- it only increments `attemptCount` on the existing one and
  re-dispatches the same `OutboxMessage.id()`. This directly satisfies "retries do not create
  new logical event IDs" and "delivery remains explicitly at-least-once" with zero new code:
  `outbox_event.id` already is the delivery ID.
- **Event coverage is 4-of-5, not 5-of-5.** `protection.decision.created`, `recovery.completed`,
  and `policy.activated` already flow through the outbox today. `recovery.manual_review.required`
  did not exist as an event but the underlying state transition did (`RecoveryApplicationService
  .confirmIdentity()` already moves a flow into `RecoveryStatus.MANUAL_REVIEW`) -- adding the
  event and one publish call there was small, well-defined work, done as part of this issue.
  `audit.integrity.failed` has no natural trigger point: Postgres already *prevents* audit
  mutation at the database level (`audit.reject_audit_mutation()`, issue #20/#25) rather than
  allowing a bad write to succeed and be *detected* later. A real "integrity failed" event would
  require inventing a periodic checksum/hash-chain verification job -- a separate, materially
  larger feature. **Explicitly deferred**, not implemented by this issue.

## Decision

### `WebhookEventPublisher` replaces the logging stub

A new `webhook` module provides `WebhookEventPublisher implements OutboxEventPublisher`,
auto-registered in place of `LoggingOutboxEventPublisher` (no change needed to the outbox module
itself -- that is exactly what its `@ConditionalOnMissingBean` was for). For each `OutboxMessage`,
it finds every `ACTIVE` subscription whose `eventTypeFilter` is null or matches the message's
event type, and delivers to each over HTTP (`RestClient`, timeout-bounded). If there are no
matching subscriptions, `publish()` is a no-op success -- this is also what keeps every existing
outbox-touching test in this repository passing unchanged, since none of them create webhook
subscriptions.

If *any* delivery fails, `publish()` throws, and the existing `OutboxRelay` retry/backoff/
dead-letter machinery takes over completely unmodified.

### Signing and headers

Each delivery is signed with HMAC-SHA256 over `timestamp.deliveryId.rawBody` (`WebhookSigner`,
using the subscription's own secret), matching this codebase's existing HMAC style
(`AccountPseudonymizer`, `HmacChallengeCodeHasher`) but keyed per-subscription rather than by one
global secret. Headers: `X-Webhook-Signature`, `X-Webhook-Timestamp` (epoch seconds), `X-Webhook-
Delivery-Id` (`outbox_event.id`), `X-Webhook-Schema-Version` (extracted from the envelope actually
being delivered, not assumed to always be current). Signing over the *exact* raw body string that
is also sent as the request body -- not a re-serialized copy -- is what lets receivers verify
against the exact bytes they received.

### Subscription secrets: a dedicated cipher, not the `crypto` module

`WebhookSecretCipher` encrypts each subscription's secret at rest under a single, static,
app-level AES-256 key (SHA-256-derived from a configured passphrase, mirroring `KeyEncryptionKey
Resolver`'s derivation trick from ADR 0025). This is deliberately **not** built on the `crypto`
module's `FieldEncryptionService`: that mechanism exists to make the same plaintext always
resolve to the same per-subject key, so a subject's data can later be crypto-shredded by
identifier. A webhook secret is an opaque, randomly generated value with no "identifier" and no
shredding requirement -- a disabled or rotated subscription's old secret simply stops being used.
Reusing the subject-key model here would borrow infrastructure built for a different problem and
add an irrelevant `crypto.subject_key` row per secret. `WebhookSecretGenerator` produces a random
256-bit secret (hex-encoded) at creation and at each rotation.

### Never returning the secret again

`WebhookSubscriptionService.create` and `.rotateSecret` are the only two operations that ever
return plaintext secret material (`WebhookSecretIssued`), and each does so exactly once, in the
response to that call. `WebhookSubscriptionView` (returned by `list`) has no secret field at all
-- there is no code path from the persisted ciphertext back to an API response.

### Demo receiver: in-process, not a second deployable

`DemoWebhookReceiverController` (`/demo/webhook-receiver`, `permitAll`) is a reference receiver
implementation: it recomputes the HMAC-SHA256 signature against its own configured secret and
compares in constant time, rejects a timestamp older than a configurable skew, and rejects a
previously seen `X-Webhook-Delivery-Id` (a bounded in-memory LRU set). This runs in the same
process rather than as a second service, matching this codebase's existing "simulated provider"
approach (ADR 0004) rather than introducing a new deployable per CLAUDE.md's constraint on
microservices without an accepted ADR.

### Delivery history

`GET /api/v1/outbox` (optional `status` filter) was added to the existing outbox admin API,
returning `OutboxEventSummary` (id, aggregate type/id, event type, status, attempt count,
occurred/published/dead-lettered timestamps). This satisfies "expose delivery history ... and
dead-letter state" directly from the outbox's own status machine -- there is no separate
"webhook delivery log" duplicating the same data. "Redelivery" was already covered by the
existing `POST /api/v1/outbox/{eventId}/requeue` from ADR 0023.

### Administrative audit

All subscription admin actions (create, rotate-secret, enable, disable) log a structured line
with the actor, matching `OutboxAdminApplicationService.requeue`'s exact existing convention --
the only "administrative action audit" mechanism this codebase has today. No new audit-trail
infrastructure was introduced.

## Alternatives considered

- **Implementing `audit.integrity.failed` as part of this issue** -- rejected; it requires a new
  periodic verification job with its own design questions (what gets checksummed, how far back,
  what "failed" even means when the database already rejects bad writes at write time), which is
  a separate feature, not a wiring gap like `recovery.manual_review.required` was.
- **Reusing `crypto.FieldEncryptionService` for subscription secrets** -- rejected; see Decision
  above (semantic mismatch between per-subject crypto-shredding and one opaque static secret).
- **A join table for multi-event-type subscriptions** -- rejected for this issue; a single
  nullable `event_type_filter` column (null = all events) covers "subscribe to one type" and
  "subscribe to everything," the two cases this issue actually needs. Multi-select filtering is
  deferred.
- **Allowing subscription deletion** -- rejected; only create/list/enable/disable/rotate-secret
  are exposed. Disabling preserves the subscription's history; deleting would not, and nothing in
  the issue requires it.

## Consequences

### Positive

- zero new retry/backoff/dead-letter infrastructure: this issue is almost entirely a new
  publisher plugged into an existing, already-tested delivery loop;
- every existing outbox-touching test keeps passing unchanged, because "no matching subscriptions"
  is a defined no-op, not an error;
- subscription secrets are never persisted in plaintext and never returned after issuance;
- the demo receiver proves the full signing/timestamp/replay contract against a real HTTP call in
  this repository's own test suite, not just in documentation.

### Negative

- `audit.integrity.failed` remains unimplemented; any consumer relying on the full 5-event set
  from the issue text will not receive that event;
- a failed delivery to *any one* matching subscription fails the whole outbox event, causing a
  full retry (and thus a duplicate delivery attempt) to subscriptions that already succeeded --
  an accepted consequence of the explicit at-least-once guarantee, but worth calling out: a
  receiver's own delivery-ID deduplication (as the demo receiver implements) is what makes this
  safe, not anything on the sending side;
- the webhook secret encryption key is a single static app-level key with no rotation job of its
  own (unlike ADR 0025's per-subject KEK rotation) -- acceptable because there are few
  subscriptions, not one per end-user subject, so a manual re-encryption would be a reasonable
  one-off operation if this key ever needed to rotate;
- `event_type_filter` is a single nullable column, not a multi-select list.

## Guardrails

- `WebhookEventPublisher` is the only code path that decrypts a subscription secret, and only
  immediately before signing one delivery;
- `WebhookSubscriptionView` (the only type returned by `list`) has no secret-bearing field, proven
  by `WebhookAdminControllerTest.listNeverIncludesASecretField`;
- `WebhookDeliveryIntegrationTest` proves, against real Postgres and a real HTTP round trip to the
  in-process demo receiver: a matching active subscription is delivered to and accepted; a
  redelivery of the same event is rejected by the receiver as a duplicate; a disabled subscription
  and a non-matching event-type filter are both skipped; a wrong subscription secret fails
  signature verification at the receiver;
- `DemoWebhookReceiverControllerTest` proves the receiver rejects a stale timestamp, rejects an
  invalid signature, and rejects a duplicate delivery ID, independent of the publisher.

## Migration/compatibility implications

`V22__add_webhook_subscriptions.sql` creates the `webhook` schema and `webhook_subscription`
table, and extends the ADR 0024 least-privilege grants to it. No existing table is altered.
Existing outbox rows and behavior are unaffected: with zero subscriptions configured, delivery
behavior is identical to `LoggingOutboxEventPublisher`'s previous no-op-on-nothing-to-do shape,
just without the log line for every event (a delivery log line is now emitted only when an actual
HTTP delivery is attempted).

## Revisit criteria

- when `audit.integrity.failed` gets its own periodic verification design;
- when a subscription needs to filter on more than one event type;
- if webhook subscription volume grows enough that the static secret-encryption key needs its own
  rotation job (mirroring `SubjectKeyRewrapJob`'s pattern from ADR 0025);
- if a receiver needs delivery confirmation beyond HTTP status (e.g. a signed receipt).

## Links

- Issue #47
- [ADR 0023](0023-outbox-claiming-backoff-and-dead-letters.md) (the delivery loop this issue plugs
  into, unmodified)
- [ADR 0025](0025-envelope-encryption-key-rotation-and-crypto-shredding.md) (the per-subject
  encryption model this issue's secret storage deliberately does not reuse)
- [Data classification](../architecture/README.md#data-classification)
- Tests: `WebhookSecretCipherTest`, `WebhookSignerTest`, `WebhookAdminControllerTest`,
  `WebhookSubscriptionServiceIntegrationTest`, `WebhookDeliveryIntegrationTest`,
  `DemoWebhookReceiverControllerTest`, `RecoveryApplicationServiceTest
  .confirmIdentityPublishesManualReviewRequiredForManualReviewClassification`
