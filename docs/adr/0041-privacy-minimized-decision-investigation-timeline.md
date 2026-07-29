# ADR 0041: Privacy-minimized decision investigation timeline

- Status: Accepted
- Date: 2026-07-29
- Related issues: #70, #171
- Related ADRs: 0010, 0020, 0023, 0027, 0040

## Context

ADR 0040 introduced an authorized, privacy-minimized queue and correlation-ID search contract. That contract intentionally returns only enough information to locate a decision. Frontend issue #70 needs a second read surface that explains why a decision occurred and which downstream events followed it.

The required evidence spans several modules:

- the audit trace and ordered reason contributions;
- signal, policy and execution provenance retained with the trace;
- challenge state owned by the challenge module;
- recovery authorization state owned by the recovery module;
- payload-bearing delivery state owned by the outbox module.

Direct repository access from a controller would violate module ownership. Returning the existing audit view, normalized context map, challenge values, outbox payloads or persistence entities would expose fields that are not suitable for an operator console. A browser-side join would also distribute authorization, minimization and ordering rules into client code.

## Decision

AccountShield exposes one narrow read-only operation:

```text
POST /api/v1/operator/decisions/investigate
```

The request contains one validated opaque UUID decision reference in a JSON body. The reference does not appear in a path or query string.

The operation:

1. requires the backend role `SECURITY_OPERATOR` through the existing operator route rule;
2. loads the owning audit trace through `DecisionInvestigationQuery`;
3. obtains challenge, recovery and outbox projections through public module-owned read ports;
4. returns one dedicated minimized projection rather than internal entities or generic maps;
5. masks the retained subject reference before serialization;
6. emits reason code, contribution and stable ordinal without unrestricted detail maps;
7. exposes bounded signal, policy and execution provenance fields individually;
8. exposes challenge and recovery summaries without values, tokens, provider payloads or unrestricted subject identifiers;
9. exposes outbox metadata without event payload or failure text;
10. returns `Cache-Control: no-store`;
11. maps invalid, absent and unavailable investigations to stable redacted Problem Details.

## Module-owned read ports

The challenge, recovery and outbox modules publish small investigation interfaces. Their implementations remain internal to the owning module and map persistence entities to immutable, privacy-minimized records.

The audit implementation composes these public projections. It does not import another module's `internal.persistence` package.

This keeps the aggregation in the backend while preserving module ownership and allows each module to change persistence independently of the HTTP contract.

## Availability semantics

Each optional downstream section is reported as one of:

- `AVAILABLE` — authorized recorded data was returned;
- `NOT_APPLICABLE` — the decision outcome did not require that downstream capability;
- `UNAVAILABLE` — the capability was expected but no trustworthy projection could be returned.

An empty list is therefore not used to imply confirmed absence when a section was expected. The top-level `partial` flag is true when required provenance or an expected section is unavailable.

## Deterministic timeline

The backend creates the timeline before serialization. Entries are ordered by:

1. timestamp ascending;
2. documented event-kind priority;
3. stable opaque reference.

The event-kind priority resolves ties produced by one transaction using one clock instant. Request receipt precedes decision recording, which precedes downstream outbox recording for equal timestamps. Published, consumed and dead-letter events follow their originating entries.

The API emits ISO-8601 instants. The frontend may format them for a user-selected timezone but must retain the source instant and communicate the displayed timezone.

## Privacy boundary

The response excludes:

- raw account references;
- request fingerprints and normalized-context maps;
- IP, device and network payloads;
- challenge values and provider material;
- recovery tokens or authorization secrets;
- outbox payloads and last-error text;
- SQL, exception details and persistence entities.

The response may expose opaque operational references needed to correlate already-authorized records. Browser and BFF telemetry must not use these values as metric tags or unrestricted log fields.

## Alternatives considered

### Browser-side composition

Rejected because it would require several protected calls, duplicate minimization rules and risk exposing intermediate raw responses to browser JavaScript.

### One controller querying all repositories

Rejected because it bypasses module APIs and couples the HTTP layer to persistence schemas.

### Reuse `DecisionTraceView`

Rejected because it contains sensitive subject, fingerprint, normalized context and reason details beyond the console's need.

### Use a GET path containing the decision reference

Rejected for the same URL-retention reasons documented in ADR 0040.

### Treat missing downstream rows as empty success

Rejected because unavailable evidence and confirmed non-applicability have different operational meanings.

## Consequences

### Positive

- frontend #70 consumes one generated operation behind its BFF adapter;
- authorization, minimization and ordering remain backend-authoritative;
- module ownership is preserved through public read ports;
- partial evidence is represented honestly;
- timeline output is deterministic and reproducible in tests;
- raw event and provider payloads never cross the operator API boundary.

### Negative

- the audit read path coordinates multiple module queries;
- the projection intentionally duplicates selected fields from module models;
- new timeline sources require explicit port, schema, privacy and ordering review;
- a detail response can become large, so future additions must remain bounded.

## Executable guardrails

- controller tests assert `no-store`, stable Problem Details and prohibited-field absence;
- security integration tests cover missing authentication, wrong role and `SECURITY_OPERATOR`;
- PostgreSQL integration tests verify masking, provenance, outbox projection and timeline ordering;
- OpenAPI compatibility tests review future request and response changes;
- no investigation controller or audit adapter may import another module's internal persistence package.

## Revisit criteria

Revisit this decision if:

- measured query cost requires a separately maintained read model;
- tenant isolation adds a mandatory resource scope beyond the operator role;
- a timeline source cannot be queried consistently enough for the current partial-data semantics;
- evidence export needs signed canonical records rather than this operational projection;
- response size requires bounded section pagination.
