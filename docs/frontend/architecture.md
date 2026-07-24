# Frontend architecture

## Decision

AccountShield will include a frontend application in the same repository under `frontend/`. It is a security-operations console, not an identity provider or end-user account portal.

## Principles

- Read-only first. Mutations are enabled only after the backend exposes authenticated, authorized, audited contracts.
- Contract driven. TypeScript clients are generated from the published OpenAPI document.
- Backend for Frontend. Browser code does not directly manage long-lived backend credentials.
- Explainability first. Decision provenance and timelines are the primary product surfaces.
- Privacy by default. Account, device, network, and challenge data are synthetic, masked, minimized, or pseudonymized.
- Accessible operations. Keyboard navigation, semantic tables, clear status text, empty/loading/error states, and non-color-only meaning are mandatory.
- Deterministic demos. Fixtures and scenario-lab outputs must reproduce stable screenshots and E2E tests.

## Initial information architecture

- Overview
- Decisions
- Decision investigation
- Recoveries
- Policies
- Replay and simulation
- Operations

## Backend dependencies

The shell, fixtures, design tokens, and read-only views can evolve immediately. Secure mutations depend on backend issues covering authentication/RBAC, maker-checker policy approval, purpose-bound step-up, recovery authorization, data masking, and dead-letter operations.

## API boundary

Pages consume feature adapters. Feature adapters consume generated OpenAPI clients or fixture implementations. Components never call raw backend endpoints directly.

```text
page -> feature query/service -> adapter -> generated client -> BFF -> Spring API
```

## Security constraints

- no access token in localStorage;
- no raw sensitive identifiers in URLs, logs, analytics, or browser console;
- no UI-only authorization;
- no generic verified challenge reused for privileged actions;
- correlation IDs are safe operational references, not authorization;
- administrative commands require explicit confirmation and auditable reasons.

## Accepted architecture decisions

- [ADR 0011 — Colocate the Next.js operations console](../adr/0011-colocate-nextjs-operations-console.md)
- [ADR 0012 — Adopt a read-only-first operator console](../adr/0012-adopt-read-only-first-operator-console.md)
- [ADR 0013 — Use a backend-for-frontend security boundary](../adr/0013-use-backend-for-frontend-security-boundary.md)
- [ADR 0014 — Generate frontend API clients from OpenAPI](../adr/0014-generate-frontend-api-clients-from-openapi.md)
- [ADR 0015 — Use deterministic frontend data sources](../adr/0015-use-deterministic-frontend-data-sources.md)
- [ADR 0016 — Prefer React Server Components](../adr/0016-prefer-react-server-components.md)
