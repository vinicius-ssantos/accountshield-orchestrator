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

## Planned slices

1. frontend CI, container, and Compose integration;
2. fixture adapter and stable domain view models;
3. generated OpenAPI client;
4. decisions list and investigation timeline;
5. recovery read-only queue;
6. replay comparison;
7. OIDC/JWT roles and secure mutations;
8. policy rollout and outbox/DLQ operations.
