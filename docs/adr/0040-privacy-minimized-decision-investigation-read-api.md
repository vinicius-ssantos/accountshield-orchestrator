# ADR 0040: Privacy-minimized decision investigation read API

- Status: Accepted
- Date: 2026-07-28
- Related issues: #69, #134
- Related ADRs: 0010, 0011, 0027, 0029

## Context

The operations console needs to locate recent protection decisions and search by correlation ID. The existing public controller only creates decisions, while the internal `DecisionTraceView` contains fields that are inappropriate for browser-facing operations: raw account reference, request fingerprint, normalized context, and reason details.

Correlation IDs were request-scoped MDC metadata. They were returned in headers and Problem Details but were not persisted with `audit.decision_trace`, so a deterministic historical lookup would otherwise depend on logs.

## Decision

AccountShield exposes a narrow, read-only operator operation:

```text
POST /api/v1/operator/decisions/search
```

The operation accepts an exact correlation ID or an allowlisted combination of bounded filters in a JSON body. Correlation IDs are absent from URL paths and query strings.

The backend:

1. requires the `SECURITY_OPERATOR` role;
2. persists the validated request correlation ID with each new decision trace;
3. assigns synthetic `legacy-<generated-id>` correlations to historical and direct legacy writers;
4. queries through the public `DecisionInvestigationQuery` read port;
5. reads only `audit.decision_trace`, including the retained event-type projection, rather than joining across module-owned tables;
6. returns a dedicated minimized projection instead of `DecisionTraceView`, JPA entities, or normalized audit context;
7. uses deterministic keyset ordering by `decided_at DESC, id DESC` and an opaque cursor;
8. bounds page size, time windows, text lengths, enum values, and supported filters;
9. returns `Cache-Control: no-store`;
10. maps invalid and unavailable searches to stable Problem Details without raw exception text;
11. adds indexes for exact correlation lookup, event filtering, and deterministic recent-decision traversal.

The response may include the decision reference, correlation ID, event type, outcome, score/band, policy key/version, timestamp, and bounded degraded/simulated/provenance indicators. It excludes account references, request fingerprints, normalized-context maps, reason details, challenge/recovery material, provider payloads, and persistence entities.

## Audit-hash boundary

`correlation_id` is append-only operational lookup metadata, but it is not added retroactively to the canonical record-hash schema established by ADR 0027. Changing the canonical schema requires an explicit version transition and compatibility rules for historical links. The database append-only trigger still prevents ordinary mutation of the complete row, while the existing hash verifies the previously defined canonical evidence fields.

A future requirement to make correlation ID part of exported cryptographic evidence must introduce a new canonical schema version rather than silently changing the current hash input.

## Alternatives considered

### Expose `DecisionTraceView` directly

Rejected because it contains sensitive and high-cardinality fields and couples external consumers to the internal audit representation.

### Use `GET` with correlation ID in the URL

Rejected because URLs are routinely retained in browser history, reverse-proxy access logs, analytics, screenshots, and monitoring systems.

### Search application logs

Rejected because logs are not the source of truth, may be sampled or retained differently, and do not provide transactional consistency with the decision record.

### Join the protection module read model

Rejected for this slice because the retained audit context already contains the event type. Keeping the query inside the audit table avoids a hidden cross-module read dependency and keeps the modular-monolith boundary executable.

### Offset pagination

Rejected because concurrent inserts make large offsets expensive and can cause unstable traversal. Keyset pagination preserves deterministic bounded reads.

### Generic filtering or repository proxy

Rejected because it expands the attack surface, weakens query-cost bounds, and risks exposing newly added audit fields without review.

## Consequences

### Positive

- #69 can consume a real authorized contract instead of guessing backend response shapes;
- sensitive audit fields never cross the operator API boundary;
- correlation lookup is transactional and indexed;
- pagination remains stable and bounded;
- authorization and non-cacheability are enforced server-side;
- the audit module does not acquire reverse dependencies on protection, policy, or risk modules.

### Negative

- correlation metadata increases audit-row and index size;
- a POST read operation is less conventional than GET, intentionally offset by `no-store` and URL privacy;
- historical rows receive synthetic legacy references rather than reconstructed request correlation;
- event filtering depends on the retained `protectionEventType` audit-context field;
- additional filters require explicit schema and index review.

## Executable guardrails

- security integration tests reject callers without `SECURITY_OPERATOR`;
- controller tests prove `no-store`, minimized serialization, stable validation, and exception-detail redaction;
- PostgreSQL integration tests prove correlation persistence, legacy-writer defaults, scoped filtering, and cursor traversal;
- migration verification covers the additive non-null column and indexes without mutating existing append-only rows;
- OpenAPI compatibility checks review future request/response changes;
- no client-facing type embeds `DecisionTraceView` or `normalizedContext`.

## Migration and compatibility

Migration V25 adds `audit.decision_trace.correlation_id` with a generated synthetic default, so existing rows and direct legacy writers are populated without executing an `UPDATE` against the append-only trigger. It adds correlation, queue-order, and event-expression indexes. Existing decision creation requests and response models remain unchanged.

## Revisit criteria

Revisit this decision if:

- client isolation requires correlation searches to be scoped by an authenticated tenant dimension;
- measured query plans require composite indexes for a dominant filter combination;
- correlation becomes part of signed evidence and requires a new audit canonical-schema version;
- the console requires analytical search better served by a separately reviewed read model;
- authorization evolves from the current role to resource- or client-scoped policies.
