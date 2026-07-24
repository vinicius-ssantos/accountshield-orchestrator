# ADR 0011: Colocate the Next.js operations console in the application repository

- Status: Accepted
- Date: 2026-07-23

## Context

AccountShield needs an operator-facing console for investigating protection decisions, recoveries, policy behavior, deterministic replay, and operational delivery state.

The console and the Spring application share domain language, OpenAPI contracts, security constraints, delivery gates, and architectural documentation. Creating a separate repository now would introduce duplicated governance, cross-repository coordination, and version-alignment work before independent deployment has demonstrated value.

The frontend must still be able to evolve into an independently deployable artifact without becoming structurally coupled to backend implementation details.

## Decision

Place the frontend application under `frontend/` in the AccountShield repository and implement it with Next.js App Router and strict TypeScript.

The repository remains a single product repository with independently scoped backend and frontend build jobs. The frontend may have its own container image and deployment lifecycle later, but repository separation is not required for deployment independence.

Shared product documentation remains under `docs/`, and frontend-specific operating instructions remain under `frontend/`.

## Consequences

### Positive

- domain, API, security, and UI changes can be reviewed together;
- OpenAPI compatibility gates can run in one CI workflow;
- architectural decisions remain in a single ADR sequence;
- contributors have one issue tracker and one source of product truth;
- fixture scenarios can evolve alongside backend domain behavior;
- repository colocation does not prevent separate deployment artifacts.

### Negative

- CI must avoid running every expensive frontend and backend task for unrelated changes indefinitely;
- ownership boundaries may become less obvious as the team grows;
- repository size and dependency tooling increase;
- release automation must distinguish backend, frontend, and documentation changes.

## Guardrails

- frontend code must not import backend implementation code;
- integration occurs through published contracts, not shared internal classes;
- frontend and backend keep separate dependency manifests and build commands;
- CI exposes distinct frontend and backend quality gates;
- future container and deployment definitions must preserve independent rollout capability;
- repository-level documentation must clearly identify the frontend as an operator console, not an identity provider.

## Alternatives considered

### Separate frontend repository immediately

Rejected because it creates coordination and versioning overhead without current organizational or deployment evidence.

### Server-rendered UI inside Spring MVC

Rejected because the intended operator experience requires a modern component model, typed contract integration, and an independently evolvable browser application.

### No frontend in this project

Rejected because decision explainability, recovery investigation, replay comparison, and operational workflows are core product capabilities that benefit from a dedicated operator surface.

## Revisit criteria

Revisit this decision when:

- frontend and backend require materially different access controls or contributor ownership;
- repository size or CI duration creates sustained delivery friction;
- independent release governance cannot be implemented cleanly in one repository;
- the console becomes a reusable product serving multiple independently governed backend systems.
