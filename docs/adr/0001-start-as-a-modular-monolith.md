# ADR 0001: Start as a modular monolith

- Status: Accepted
- Date: 2026-07-15

## Context

AccountShield contains several cohesive capabilities: protection request orchestration, risk assessment, policy evaluation, audit, challenges, recovery, abuse controls, replay, and simulation.

Those capabilities have distinct domain responsibilities, but the project begins with one maintainer, one delivery pipeline, no measured independent scaling requirements, and a strong need for transactional consistency while the domain model is still evolving.

Starting with independently deployed services would introduce network failure modes, distributed tracing, schema coordination, event-versioning pressure, and additional infrastructure before the module boundaries have been validated through working vertical slices.

## Decision

AccountShield will start as a Spring Boot modular monolith using Spring Modulith to discover and verify application-module boundaries.

Each top-level domain package is an application module. Modules expose intentional public APIs and keep persistence entities, repositories, adapters, and implementation details internal. Cross-module collaboration occurs through public APIs or domain events.

The architecture test must call `ApplicationModules.verify()` in CI. A dependency that violates the declared module structure is treated as a build failure.

PostgreSQL is shared initially, but table ownership remains explicit by module. A module must not access another module's repository or persistence entity directly.

## Consequences

### Positive

- domain boundaries are explicit without introducing network distribution;
- transactions remain local while idempotency and audit models are established;
- development, testing, and local execution remain accessible;
- module coupling is visible and mechanically verified;
- future extraction can be based on measured operational pressure.

### Negative

- one deployment still contains multiple capabilities;
- poor discipline could turn the codebase into a conventional monolith despite package boundaries;
- shared database infrastructure requires explicit ownership rules;
- extraction later may require event and persistence migration work.

## Guardrails

- no shared generic `service`, `repository`, `entity`, or `util` packages;
- no direct access to another module's internal packages;
- no repository sharing across modules;
- no distributed service extraction without an ADR supported by scaling, ownership, deployment, governance, or failure-isolation evidence;
- every new module must arrive with a vertical slice, public contract, and architecture-test coverage.

## Revisit criteria

This decision may be revisited when at least one module has a demonstrated need for independent deployment, substantially different scaling, isolated data governance, independent ownership, or stronger runtime failure isolation.
