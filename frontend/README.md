# AccountShield Security Operations Console

Initial frontend foundation for the AccountShield operator experience.

## Product direction

The console is designed for security operators, policy administrators, simulation analysts, and operational readers. The first releases prioritize investigation and explanation over administrative mutation.

Core workflow:

1. find a decision by correlation ID;
2. inspect risk signals, reasons, policy provenance, challenge, recovery, audit, and outbox timeline;
3. replay the historical decision without side effects;
4. review recovery only after backend RBAC and fresh step-up authorization are available.

## Current scope

This pull request intentionally starts in fixture-driven, read-only mode:

- Next.js App Router and strict TypeScript;
- dark operations-console shell;
- initial navigation and dashboard;
- synthetic metrics and decisions;
- no authentication bypass;
- no real recovery approval, policy activation, rollback, or dead-letter replay.

## Local development

```bash
cd frontend
cp .env.example .env.local
npm install
npm run dev
```

Open `http://localhost:3000`.

## Architecture direction

- generated OpenAPI client behind adapters;
- server-side/BFF boundary for credentials and backend access;
- RFC 9457 Problem Details normalization;
- correlation-ID propagation;
- TanStack Query when live remote state is introduced;
- Playwright for golden-path E2E coverage;
- all sensitive identifiers masked by default;
- authorization enforced by the Spring backend, never only by the UI.

## Accepted architecture decisions

- ADR 0011: colocate the Next.js operations console in this repository;
- ADR 0012: adopt a read-only-first operator console;
- ADR 0013: use a backend-for-frontend security boundary;
- ADR 0014: generate API clients from the published OpenAPI contract;
- ADR 0015: use deterministic synthetic data sources;
- ADR 0016: prefer React Server Components and minimize client boundaries.

See [`docs/frontend/architecture.md`](../docs/frontend/architecture.md) for links and consolidated constraints.

## Delivered foundation

- frontend CI with type generation, lint, typecheck, and production build;
- fixture adapter and stable decision view models;
- deterministic data-source selection;
- accessible overview, planned routes, and App Router states;
- security and architecture ADRs.

## Planned slices

1. frontend lockfile, container, and Compose integration;
2. generated OpenAPI client and compatibility gate;
3. decisions list and investigation timeline;
4. recovery read-only queue;
5. replay comparison;
6. OIDC/JWT roles and secure mutations;
7. policy rollout and outbox/DLQ operations;
8. Playwright golden-path and adversarial browser tests.
