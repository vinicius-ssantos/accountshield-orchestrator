# ADR 0014: Generate frontend API clients from the published OpenAPI contract

- Status: Accepted
- Date: 2026-07-23

## Context

The AccountShield console will consume decision, recovery, policy, replay, and operations APIs. Hand-written request and response types drift easily from backend behavior, especially when endpoints evolve, enum values change, fields become required, or error schemas are standardized.

Allowing pages and components to call raw endpoints directly would duplicate URL construction, headers, error parsing, correlation propagation, authentication behavior, and retry rules across the application.

The frontend needs compile-time contract feedback without coupling presentation code directly to generated implementation details.

## Decision

Publish the backend OpenAPI document as the authoritative HTTP contract and generate the TypeScript API client used by the frontend.

Generated code will live behind feature adapters or services. Pages and components depend on stable frontend view models and feature interfaces, not directly on generated transport types.

The intended dependency direction is:

```text
page or component -> feature service/query -> adapter -> generated OpenAPI client -> BFF -> Spring API
```

Contract generation and compatibility validation will become CI gates before live API integration is considered complete.

## Contract behavior

- backend response and request schemas originate from the published OpenAPI document;
- generated files are not edited manually;
- regeneration is deterministic and performed by a pinned tool version;
- breaking contract changes must be visible in pull-request review;
- generated transport types are mapped to frontend view models where presentation or privacy needs differ;
- RFC 9457 Problem Details is normalized at the adapter or BFF boundary;
- correlation identifiers are propagated through a documented header and surfaced only where safe;
- unknown enum values and backward-compatible additions must fail safely or map to an explicit fallback state.

## Consequences

### Positive

- contract drift becomes a build or review failure instead of a runtime surprise;
- endpoint and schema changes are visible in generated diffs;
- request construction and transport error handling are centralized;
- frontend domain-facing types remain independent from transport-specific naming;
- fixtures can implement the same feature interfaces as live adapters;
- integration tests can verify the published contract and generated client together.

### Negative

- generated code increases repository noise unless review and placement are controlled;
- generator upgrades can create large diffs unrelated to business changes;
- mapping between transport types and view models adds deliberate code;
- OpenAPI quality becomes a delivery dependency;
- build tooling must obtain or produce the contract before generation.

## Guardrails

- components must not call raw backend URLs directly;
- generated client code must not contain embedded credentials or environment-specific hosts;
- generator versions and options must be pinned;
- generated code must be reproducible from committed configuration and a published contract;
- contract generation must not silently overwrite reviewed handwritten code;
- adapters must minimize sensitive fields before data crosses into client components;
- retry behavior must not automatically repeat non-idempotent commands without an idempotency contract;
- authorization failures must remain distinguishable from absence only when the backend contract intentionally permits that distinction.

## Alternatives considered

### Hand-write fetch calls and TypeScript interfaces

Rejected because it creates duplicated transport logic and allows backend/frontend drift to accumulate silently.

### Share Java-generated models directly with TypeScript

Rejected because backend persistence or domain types are not stable browser contracts and may expose fields the console should not receive.

### Use GraphQL instead of OpenAPI

Not selected because the existing backend is REST-oriented and OpenAPI already provides a suitable published contract. A protocol change is not justified by the frontend foundation.

## Revisit criteria

Revisit this decision when:

- the backend adopts a different authoritative interface-description format;
- generated-client maintenance costs consistently exceed the contract-safety benefit;
- the console consumes multiple heterogeneous APIs that require a dedicated aggregation schema;
- a typed RPC approach provides equivalent compatibility, security, and tooling guarantees through a new ADR.
