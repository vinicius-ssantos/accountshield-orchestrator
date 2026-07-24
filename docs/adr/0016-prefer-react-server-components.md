# ADR 0016: Prefer React Server Components and minimize client boundaries

- Status: Accepted
- Date: 2026-07-23

## Context

The AccountShield console is primarily an investigation and operations application. Many views render authorized server-derived data, policy provenance, timelines, metrics, and replay results. Making every component a client component would send more JavaScript to the browser, increase hydration work, duplicate data-fetching concerns, and make it easier to expose transport details or sensitive data unnecessarily.

Some interactions still require client execution, including retry controls, local filtering, dialogs, focus management, progressive form behavior, and browser-only APIs.

The application needs a clear default that keeps client execution deliberate without preventing interactive operator workflows.

## Decision

Use React Server Components as the default for pages, layouts, data loading, and non-interactive presentation.

Add the `"use client"` directive only at the smallest practical interaction boundary. Client components receive minimized, serializable view models rather than raw backend responses, credentials, or server-only configuration.

Server-side data access goes through feature services and data-source adapters. Client-side data access is introduced only when the interaction requires browser-managed server state, polling, optimistic updates, or live refresh behavior.

## Component boundary rules

- pages and layouts remain server components unless a concrete browser requirement prevents it;
- server-only modules are not imported into client components;
- client boundaries are placed around interactive leaves rather than entire pages;
- props crossing into client components are serializable and minimized;
- access tokens, refresh tokens, secrets, internal headers, and unrestricted backend payloads never cross the boundary;
- sensitive data is formatted, masked, and reduced on the server whenever possible;
- loading, error, and not-found states use App Router conventions;
- browser state represents temporary interaction state, not an alternate authorization or domain source of truth.

## Consequences

### Positive

- less JavaScript and hydration work is sent to the browser;
- server-side credentials and configuration remain outside client bundles;
- data loading aligns naturally with the BFF and adapter boundaries;
- non-interactive operator views remain simpler and easier to reason about;
- sensitive-data minimization can occur before serialization;
- interactive components can still be introduced incrementally.

### Negative

- developers must understand server/client composition rules;
- some component libraries assume client execution and require wrappers;
- moving a boundary can require refactoring props and data ownership;
- browser-side testing and server-component testing need different strategies;
- real-time or highly interactive surfaces may need more explicit client-state architecture.

## Guardrails

- `"use client"` must not be added to a layout or large feature subtree only to support one interactive control;
- client components must not read server-only environment variables;
- server actions are not considered an authorization boundary and must call backend-enforced authorized use cases;
- serialized props must not expose fields merely because they exist in a backend response;
- error objects and stack traces must not be serialized to the browser;
- server-side caching must respect operator identity, authorization scope, and data sensitivity;
- dynamic data must not be cached across users unless the cache key and backend contract make that safe;
- interactive retry and mutation behavior must preserve idempotency semantics.

## Alternatives considered

### Make the entire application client-rendered

Rejected because the console is predominantly server-data-driven and would incur unnecessary browser JavaScript, credential-handling pressure, and duplicated fetch orchestration.

### Use only server components with no client components

Rejected because accessible dialogs, retry controls, local interaction, and future operator workflows require deliberate browser-side behavior.

### Adopt a global client state framework immediately

Rejected because the foundation does not yet demonstrate cross-page client state that justifies the additional architecture. Remote server state will be addressed when live integration requires it.

## Revisit criteria

Revisit this decision when:

- the console becomes predominantly real-time and interaction-heavy;
- measured server-component complexity or navigation latency outweighs bundle and security benefits;
- the selected deployment platform cannot support the required server rendering or BFF behavior;
- a new rendering architecture provides equivalent credential isolation, data minimization, accessibility, and operational performance through a new ADR.
