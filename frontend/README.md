# AccountShield Security Operations Console

Initial frontend foundation for the AccountShield operator experience.

## Product direction

The console is designed for security operators, policy administrators, simulation analysts, and operational readers. Investigation and explanation shipped first; authenticated, audited operator mutations now build on that read-only foundation.

Core workflow:

1. find a decision by correlation ID;
2. inspect risk signals, reasons, policy provenance, challenge, recovery, audit, and outbox timeline;
3. replay the historical decision without side effects;
4. review and act on recovery, policy, and outbox state through mutations gated by a secure BFF session and backend-enforced RBAC/step-up.

## Current scope

The console runs against deterministic fixtures by default and against the live backend through the BFF once configured (`ACCOUNTSHIELD_DATA_SOURCE`):

- Next.js App Router and strict TypeScript;
- dark operations-console shell;
- decision search, investigation timeline, and evidence export;
- recovery queue with fresh-step-up-gated review (approve/deny);
- policy lifecycle (approve/activate/reject/retire) and rollout controls (start/adjust/rollback);
- outbox/dead-letter search with operator requeue;
- deterministic replay comparison;
- authenticated operator session through the BFF (login/logout/refresh, CSRF, session expiry/revocation);
- no authentication bypass;
- backend authorization remains authoritative for every mutation — the BFF session only proves a session exists, it never substitutes for a backend 403.

## Local development

Use Node.js 22 or newer and the npm version declared in `package.json`.

```bash
cd frontend
cp .env.example .env.local
npm ci --no-audit --no-fund
npm run dev
```

Open `http://localhost:3000`.

## Container and Docker Compose

From the repository root, copy the local defaults and start the console with its required API and database dependencies:

```bash
cp .env.example .env
docker compose up --build frontend
```

The local endpoints are:

- console: `http://localhost:3001`;
- console health: `http://localhost:3001/healthz`;
- Spring API: `http://localhost:8080`.

The frontend image uses Next.js standalone output, runs as a non-root user, and includes a minimal healthcheck. The Compose network provides the server-only backend origin as `http://app:8080`; this internal address is not exposed through a `NEXT_PUBLIC_*` variable or passed to browser code.

`ACCOUNTSHIELD_DATA_SOURCE` is supplied at both build and runtime. Keep it set to `fixtures` until the reviewed live BFF adapter exists; a production-like deployment must never fail over silently to fixtures.

Useful commands:

```bash
docker compose ps
docker compose logs --follow frontend
docker compose down
```

All published local ports bind to `127.0.0.1` by default. Change the documented port variables deliberately rather than exposing internal services broadly.

`npm ci` is the supported clean-install command. It consumes the reviewed `package-lock.json`, removes an existing `node_modules` directory, and fails when the manifest and lockfile disagree.

## Dependency updates

Keep direct dependency versions exact and update the manifest and lockfile together.

```bash
cd frontend
npm install --save-exact <package>@<version> --ignore-scripts --no-audit --no-fund
npm ci --no-audit --no-fund
npm run typegen
npm run lint
npm run typecheck
npm run build
```

Before committing a dependency change:

- review both `package.json` and `package-lock.json`;
- confirm package sources resolve only through `https://registry.npmjs.org/`;
- reject Git dependencies, local paths, unexpected registries, and missing integrity metadata;
- never commit `.npmrc` credentials, registry tokens, or generated `node_modules` content;
- run the same validation commands used by CI.

CI installs npm 11.4.2, enables the npm cache keyed by `frontend/package-lock.json`, validates lockfile metadata and package sources, and installs exclusively through `npm ci`.

## Testing

The frontend uses distinct test layers:

- Vitest for utilities and synchronous React components;
- React Testing Library and user-event for accessible interaction tests;
- Playwright against the production Next.js build for App Router and async Server Component flows;
- `@axe-core/playwright` for critical and serious accessibility violations.

```bash
cd frontend
npm run test
npm run test:unit
npx playwright install chromium
npm run build
npm run test:e2e
```

Use `npm run test:watch` during development, `npm run test:a11y` for the focused accessibility suite, and `npm run test:e2e:headed` for local browser debugging.

Tests must use deterministic synthetic fixtures and fixed clocks when time affects behavior. Do not record credentials, tokens, challenge material, raw identifiers, or unrestricted backend payloads in fixtures, snapshots, traces, screenshots, videos, or reports.

CI publishes Vitest coverage/JUnit output and Playwright reports, traces, screenshots, videos, and JUnit output with bounded retention. Async Server Components are validated through Playwright rather than jsdom unit tests.

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

- ADR 0012: adopt a read-only-first operator console;
- ADR 0013: use a backend-for-frontend security boundary;
- ADR 0014: generate API clients from the published OpenAPI contract;
- ADR 0015: use deterministic synthetic data sources;
- ADR 0016: prefer React Server Components and minimize client boundaries;
- ADR 0017: BFF-managed operator session cookie and CSRF design;
- ADR 0018–0021: operator recovery review, policy rollout, outbox requeue, and evidence export mutations;
- ADR 0022: colocate the Next.js operations console in this repository.

See [`docs/adr/README.md`](../docs/adr/README.md) for the full, indexed list.

See [`docs/frontend/architecture.md`](../docs/frontend/architecture.md) for links and consolidated constraints.

## Delivered foundation

- frontend CI with deterministic dependency installation, type generation, lint, typecheck, and production build;
- reviewed npm lockfile and cache configuration, containerized image, and Docker Compose integration;
- generated OpenAPI client with a drift-preventing compatibility gate;
- fixture adapter and live BFF adapter behind the same feature-adapter interface;
- deterministic data-source selection with no silent live-to-fixture fallback in production-like environments;
- decision search, investigation timeline, replay comparison, recovery queue/detail, policy lifecycle/impact views, and outbox/dead-letter views;
- authenticated operator session (login/logout/refresh, CSRF, rotation, inactivity/absolute expiry, revocation) through the BFF;
- privileged mutations — recovery review with fresh step-up, policy lifecycle and rollout control, dead-letter requeue, evidence export/verify — each gated behind its own backend readiness contract;
- frontend architecture boundaries enforced in CI (ARCH001–011) and BFF/frontend observability with strict redaction;
- performance, bundle, and Web Vitals budgets enforced in CI;
- accessible App Router states, Playwright golden-path/accessibility/leakage coverage, and security and architecture ADRs.

## Remaining portfolio polish

- reproducible screenshots and a demonstration walkthrough;
- deployment, rollback, incident, and session-revocation operating procedures;
- secure preview environments, if justified.
