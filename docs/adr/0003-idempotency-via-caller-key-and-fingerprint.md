# ADR 0003: Idempotency via caller key and request fingerprint

- Status: Accepted
- Date: 2026-07-20

## Context

Protection-decision endpoints are externally visible and must behave safely under retries from callers, load balancers, or network timeouts. Without idempotency, a single logical request can produce multiple decisions, audit records, and side effects.

The project also needs replay protection: the same normalized inputs and policy version must always yield the same outcome, and replay operations must never trigger external side effects.

## Decision

Protection decisions accept an optional caller-supplied idempotency key. When provided, the system looks up the key in an idempotency record and compares the stored request fingerprint with the current one.

### Idempotency behavior

- **No key provided:** the system uses the deterministic request fingerprint as the key. Each unique fingerprint still creates a new decision; identical payloads produce different decisions unless the caller explicitly reuses the fingerprint as the key.
- **Key present, no record:** the decision proceeds normally and the result is recorded.
- **Key present, fingerprint matches:** the stored decision is returned without creating a new protection request or audit trace.
- **Key present, fingerprint differs:** the system rejects the request with a `409 Conflict` and a stable problem detail (`IDEMPOTENCY_CONFLICT`). This prevents silent result substitution.

### Request fingerprint

The fingerprint is a SHA-256 hash over a canonical bounded representation of the normalized request fields: account reference, event type, and all signal values. It is computed deterministically and stored alongside the idempotency record.

### Storage

Idempotency records live in the `protection.idempotency_record` PostgreSQL table within the `protection` module. Each record stores the key, fingerprint, resource type and ID, serialized response payload, creation time, and expiry time. Records expire after 24 hours.

Correctness does not depend on Redis or any ephemeral store for idempotency. PostgreSQL is the sole source of truth.

### Expiry

Idempotency records have a bounded validity window (24 hours). After expiry, the same key can be reused without conflict. The expiry check is deterministic based on the current clock.

## Consequences

### Positive

- callers can safely retry without creating duplicate decisions;
- conflict detection prevents result substitution when a key is reused with different payloads;
- the response payload is serialized and cached, avoiding re-evaluation for exact duplicates;
- the idempotency record table is owned by the `protection` module with no cross-module access;
- replay determinism is preserved: the same fingerprint with the same policy always yields the same stored result.

### Negative

- storing response payloads increases idempotency record size;
- expired records require eventual cleanup (not addressed in this ADR);
- callers who omit the key and rely on implicit fingerprinting will not benefit from deduplication across requests.

## Guardrails

- idempotency keys are bounded to 128 characters;
- response payloads are stored as JSONB and are never logged;
- conflict errors return a stable problem detail that does not expose the previous fingerprint or payload;
- expired keys are treated as absent, allowing safe reuse.

## Revisit criteria

This decision may be revisited when:
- a dedicated `abuse` module is introduced with throttling and rate-limiting capabilities;
- the expiry window needs to be configurable per policy or per account;
- Redis is introduced for ephemeral high-throughput idempotency checks.
