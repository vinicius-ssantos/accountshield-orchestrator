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

Read-only views and the shell evolved first. Each privileged mutation (recovery review, policy lifecycle/rollout, dead-letter requeue, evidence export) shipped only after its own backend readiness contract — authentication/RBAC, maker-checker policy approval, purpose-bound step-up, recovery authorization, data masking, and dead-letter operations — was in place, per the epic's mutation readiness gate. Any future mutation follows the same per-capability gate rather than being assumed available.

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

## Performance and bundle budgets

Bundle size and Web Vitals are documented, CI-enforced budgets, not an unmeasured claim. See
[Frontend performance, bundle, and Web Vitals budgets](performance.md).

## Accepted architecture decisions

- [ADR 0047 — Adopt a read-only-first operator console](../adr/0047-adopt-read-only-first-operator-console.md)
- [ADR 0048 — Use a backend-for-frontend security boundary](../adr/0048-use-backend-for-frontend-security-boundary.md)
- [ADR 0049 — Generate frontend API clients from OpenAPI](../adr/0049-generate-frontend-api-clients-from-openapi.md)
- [ADR 0050 — Use deterministic frontend data sources](../adr/0050-use-deterministic-frontend-data-sources.md)
- [ADR 0051 — Prefer React Server Components](../adr/0051-prefer-react-server-components.md)
- [ADR 0052 — BFF operator session cookie and CSRF design](../adr/0052-bff-operator-session-cookie-and-csrf-design.md)
- [ADR 0053 — Operator recovery review mutation](../adr/0053-operator-recovery-review-mutation.md)
- [ADR 0054 — Operator policy rollout mutation](../adr/0054-operator-policy-rollout-mutation.md)
- [ADR 0055 — Operator outbox dead-letter requeue mutation](../adr/0055-operator-outbox-requeue-mutation.md)
- [ADR 0056 — Operator evidence export mutation](../adr/0056-operator-evidence-export-mutation.md)
- [ADR 0057 — Colocate the Next.js operations console](../adr/0057-colocate-nextjs-operations-console.md)
