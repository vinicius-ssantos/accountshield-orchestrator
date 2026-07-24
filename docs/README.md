# AccountShield documentation map

This directory is the canonical map of the AccountShield architecture, delivered capabilities, security invariants, operational guidance, and implementation roadmap.

The repository README explains the product and how to run it. The documents below explain how the system works, which guarantees are executable today, and which capabilities remain planned.

## Start here

| Question | Canonical document |
| --- | --- |
| What does the system do today? | [Feature catalog](features/README.md) |
| How is the system decomposed? | [Architecture baseline](architecture/README.md) |
| Which rules must never be violated? | [Executable invariants](architecture/invariants.md) |
| How does a protection decision work? | [Protection decisions](architecture/protection-decisions.md) |
| How does recovery work? | [Recovery architecture](architecture/recovery.md) |
| What is persisted and why? | [Persistence architecture](architecture/persistence.md) |
| Why were architectural choices made? | [Architecture decision records](adr/README.md) |
| What is the implementation order? | [Delivery roadmap](roadmap.md) |
| What are the current SLO targets? | [Operational SLO targets](operational/slo-targets.md) |

## Documentation layers

### Architecture baseline

`docs/architecture/` describes the current executable design:

- module ownership and dependency direction;
- runtime flows and trust boundaries;
- persistence responsibilities;
- concurrency and idempotency semantics;
- public contracts and internal events;
- security invariants enforced by code and PostgreSQL.

Architecture documents describe what exists on `main`, not aspirational behavior. Planned work belongs in the feature catalog and roadmap until merged.

### Feature catalog

`docs/features/README.md` is the source of truth for capability status. Every feature is classified as:

- **Implemented:** executable on `main` and covered by tests;
- **Partial:** a usable slice exists, but a named hardening gap remains;
- **Planned:** represented by an open issue but not delivered on `main`;
- **Deferred:** intentionally outside the current release scope.

Each entry links to the owning module, ADR or architecture document, and follow-up issues.

### Architecture decision records

`docs/adr/` records decisions that constrain future changes. ADRs are append-only historical records:

- accepted decisions are not silently rewritten to represent a different choice;
- material reversals require a superseding ADR;
- an ADR may be updated to clarify consequences after implementation, while preserving the original decision history.

### Roadmap

`docs/roadmap.md` groups work by delivery gate and dependency order. GitHub issues remain the execution tracker; the roadmap explains why the order exists and which issues can proceed in parallel.

## Documentation maintenance contract

A pull request must update documentation when it changes any of the following:

- module ownership or dependency direction;
- a public HTTP or event contract;
- a persisted invariant, table, constraint, trigger, or migration behavior;
- an authorization, authentication, trust, or data-classification boundary;
- idempotency, retry, concurrency, or failure semantics;
- a capability status from planned to partial or implemented;
- an operational procedure or SLO.

The minimum expected updates are:

| Change type | Required documentation |
| --- | --- |
| New feature | Feature catalog and architecture page |
| New architectural decision | New ADR and ADR index |
| Changed public API/event | Feature catalog, architecture page, OpenAPI/AsyncAPI |
| New migration/invariant | Persistence page and executable invariants |
| Security-boundary change | Relevant ADR, architecture page, threat/invariant section |
| Completed roadmap issue | Feature catalog, roadmap, and parent epic status |

## Definition of documented

A capability is considered documented only when a reviewer can identify:

1. its purpose and owning module;
2. its public entry point or triggering event;
3. its persisted state and source of truth;
4. its security and concurrency invariants;
5. its failure and retry behavior;
6. the tests proving the important guarantees;
7. known limitations and linked follow-up issues.

## Non-goals

Documentation must not:

- claim production readiness for real identity, MFA, or fraud decisions;
- describe an open pull request as merged behavior;
- hide known gaps behind generic future-work language;
- duplicate source code line by line;
- use chat history as the only record of architectural decisions.
