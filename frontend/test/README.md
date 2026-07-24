# Frontend test conventions

## Layers

- Keep pure utilities and synchronous React component tests in Vitest.
- Use Testing Library queries that reflect accessible roles and names.
- Exercise async Server Components, App Router behavior, and full workflows through Playwright.
- Keep accessibility assertions in browser tests so axe evaluates rendered production markup.

## Determinism

- Use synthetic fixtures only.
- Freeze time through `useDeterministicClock` when behavior depends on the clock.
- Avoid random identifiers unless a test supplies a fixed seed or explicit value.
- Do not call live AccountShield services or external providers from tests.

## Artifact safety

Never place credentials, cookies, tokens, challenge material, raw account/device/network identifiers, or unrestricted payloads in fixtures, snapshots, reports, traces, screenshots, or videos.

CI artifacts are diagnostic evidence with bounded retention, not an application-data store.
