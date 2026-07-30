# ADR 0042: Privacy-minimized recovery investigation read API

- Status: Accepted
- Date: 2026-07-29
- Related issues: #71, #174
- Related ADRs: 0005, 0010, 0011, 0024, 0040, 0041

## Context

The recovery module already owns a transactional state machine and mutation endpoints for initiation, identity confirmation, completion and manual review. The operator frontend needs a queue and a detail view, but the existing command responses and the narrow `RecoveryInvestigationQuery` used by the decision timeline are not suitable operator read contracts.

Reusing mutation responses would couple the console to command workflows, risk exposing raw account references, reviewer identities, challenge material or persistence entities, and could accidentally imply that a read response authorizes a later mutation. Offset pagination would also be unstable while recoveries are concurrently updated.

## Decision

Introduce a dedicated `RecoveryOperationsQuery` read port owned by the recovery module and expose two `SECURITY_OPERATOR`-only endpoints:

- `POST /api/v1/operator/recoveries/search` for bounded filters and keyset pagination;
- `POST /api/v1/operator/recoveries/investigate` for one body-based opaque recovery reference.

Both operations are read-only, return `Cache-Control: no-store`, and never call recovery command services.

The queue is ordered by `(updated_at DESC, id DESC)` and uses an opaque cursor containing the same ordering tuple. Search filters are limited to persisted status, classification, event type, derived review state, bounded time windows and bounded risk-score ranges.

The response projection is an explicit allowlist. It may expose:

- opaque recovery and originating-decision references;
- a masked subject suffix;
- status, terminal flag, classification and exact classification-rule version;
- bounded risk score and persisted timestamps;
- derived review state and a boolean indicating whether a reviewer was recorded;
- minimized challenge type, purpose, status and timestamps through `ChallengeInvestigationQuery`;
- explicit `AVAILABLE`, `NOT_APPLICABLE` or `UNAVAILABLE` section availability;
- a top-level `partial` flag when expected evidence cannot be resolved.

It must not expose raw account references, reviewer identity, authorization IDs, challenge codes or hashes, provider payloads, IP addresses, device fingerprints, evidence payloads, entities, stack traces or raw database errors.

## Alternatives considered

### Reuse recovery mutation responses

Rejected because command responses model transition results, not stable investigation projections, and would blur the authorization boundary between reading and mutating.

### Query recovery tables directly from the frontend/BFF

Rejected because PostgreSQL remains an internal source of truth and module boundaries require the recovery module to own its projection and minimization rules.

### Use URL query parameters or path references

Rejected because operational identifiers and filters would enter browser history, referrers, proxy request lines and analytics URLs. Body-based POST is deliberately used for these read operations.

### Offset pagination

Rejected because concurrent inserts and updates can cause duplicates and omissions. Keyset pagination follows the persisted deterministic ordering.

## Consequences

### Positive

- frontend #71 receives a stable, authorized contract independent of recovery commands;
- privacy minimization is enforced before data leaves the backend;
- pagination remains deterministic under concurrent writes;
- unavailable challenge evidence is represented honestly rather than inferred;
- RBAC, validation and Problem Details match the existing operator decision APIs.

### Negative

- the read model duplicates a small amount of mapping logic from the recovery aggregate;
- some filters require dedicated indexes;
- reviewer identity and unsupported attempt/provider details are intentionally unavailable to the current console;
- body-based read POSTs require explicit documentation so they are not mistaken for mutations.

## Executable guardrails

- Spring Security restricts `/api/v1/operator/recoveries/**` to `SECURITY_OPERATOR`;
- request DTOs use enums, UUID validation, maximum page size and 31-day time-window bounds;
- JDBC queries are `@Transactional(readOnly = true)` and use keyset pagination;
- controller DTOs are explicit allowlists and return `no-store`;
- stable redacted problems cover invalid search/detail, not-found and temporary unavailability;
- PostgreSQL, controller and security tests prove pagination, filtering, masking, RBAC and leakage resistance;
- migration V28 supplies indexes matching supported order/filter patterns.

## Compatibility and migration

The change is additive. Existing recovery command endpoints and state-machine invariants are unchanged. No existing response gains new sensitive fields. The new endpoints become inputs to the versioned OpenAPI compatibility gate.

## Revisit criteria

Revisit this decision when:

- operator authentication moves from the current server-side token model to the secure browser session planned by #78;
- reviewer attribution becomes an explicitly authorized audit requirement;
- recovery attempt/provider evidence is persisted in a trustworthy minimized read model;
- queue volume or query plans require a dedicated projection table or asynchronous read model.

## References

- `RecoveryOperationsQuery`
- `JdbcRecoveryOperationsQuery`
- `RecoverySearchController`
- `RecoveryInvestigationController`
- migration `V28__add_recovery_investigation_indexes.sql`
- `RecoveryOperationsIntegrationTest`
- `SecurityIntegrationTest`
