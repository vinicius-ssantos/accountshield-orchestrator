# ADR 0015: Use data-source abstractions with deterministic synthetic fixtures

- Status: Accepted
- Date: 2026-07-23

## Context

The AccountShield console must evolve before every backend read model, authentication flow, and deployment dependency is available. It also needs repeatable demonstrations, screenshots, accessibility reviews, component development, and end-to-end scenarios without copying real security data into the repository.

Directly embedding arrays in pages would couple presentation to temporary data, while allowing fixtures to drift independently from live adapters would make early UI validation misleading.

Security-oriented examples also require stronger privacy rules than ordinary placeholder data. Account identifiers, devices, network details, challenges, recovery evidence, and audit records must never be copied from production or personal environments.

## Decision

Feature code depends on explicit data-source interfaces. Each feature may provide:

- a deterministic fixture implementation for local development, review environments, tests, and demos;
- a live implementation backed by the generated OpenAPI client and BFF when the backend contract is ready.

Pages and presentation components consume feature-facing view models rather than raw fixture objects or transport responses.

Fixtures are synthetic, deterministic, reviewable, and designed to represent named product scenarios. The selected data source is explicit through server-side configuration and cannot silently fall back from a failed live request to fixture data in production.

## Fixture requirements

- all people, accounts, devices, networks, challenge data, recovery evidence, and correlation references are synthetic;
- values remain stable across runs unless a scenario intentionally tests time progression;
- timestamps use fixed clocks or explicit scenario-relative values;
- identifiers are visibly synthetic or safely pseudonymous;
- scenarios cover normal, empty, loading, degraded, unauthorized, high-risk, and failure states as applicable;
- fixture outcomes remain consistent with documented backend domain rules;
- sensitive fields are minimized even when synthetic;
- screenshots and E2E tests can select a named scenario deterministically.

## Consequences

### Positive

- UI work can proceed before live integration without embedding temporary data in components;
- demos and visual tests remain reproducible;
- the same feature contract supports fixture and live implementations;
- privacy risk is reduced because real data is prohibited;
- edge states can be designed intentionally instead of waiting for difficult backend conditions;
- transport mappings are tested at the adapter boundary rather than spread across pages.

### Negative

- fixtures require maintenance as domain contracts evolve;
- unrealistic fixture behavior can create false confidence;
- feature interfaces and view-model mappings add code before live integration;
- deterministic clocks and scenario selection need dedicated test utilities;
- the repository carries synthetic scenario data that must be reviewed like product code.

## Guardrails

- production mode must fail closed when live data is unavailable; it must not display fixtures as if they were live;
- fixture mode must be clearly visible in the console;
- fixture data must never trigger real mutations, notifications, provider calls, or backend side effects;
- no fixture may be derived by sanitizing a real production record unless a separate approved process proves irreversible anonymization;
- components must not import fixture modules directly when a feature data source is available;
- data-source selection is server-side and must not accept an arbitrary browser-controlled backend URL;
- contract changes require reviewing both live mappings and representative fixtures;
- fixture scenarios used for security behavior must state what threat or operator question they represent.

## Alternatives considered

### Hard-code temporary arrays in pages

Rejected because presentation code would become coupled to disposable data and migration to live adapters would require broad rewrites.

### Block frontend development until live APIs are complete

Rejected because information architecture, accessibility, visual states, and operator workflows can be validated safely with synthetic scenarios.

### Use mock-service interception as the only abstraction

Not selected as the architectural boundary. Network interception can support tests, but feature-facing interfaces still provide clearer contracts and avoid coupling every development mode to HTTP behavior.

## Revisit criteria

Revisit this decision when:

- the live API and test environments can deterministically produce every required operator scenario without privacy or reliability risk;
- maintaining separate fixture implementations creates more drift than value;
- a centralized scenario service provides equivalent deterministic, synthetic, offline-capable behavior through a new ADR.

Even if implementation changes, the prohibition on real sensitive data in frontend fixtures remains.
