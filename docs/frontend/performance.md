# Frontend performance, bundle, and Web Vitals budgets

Epic #41's Phase 5 ("Portfolio completion") and Gate F ("Portfolio release") require documented,
enforced performance and bundle budgets rather than an unmeasured claim of "fast enough." This
page documents the two budgets the console enforces, the numbers behind them, and where each is
checked.

## Bundle budget

**Budget:** 650KB of uncompressed first-load JavaScript per route (`performance-budget.config.mjs`).

**Enforcement:** `npm run budget:check` (`scripts/check-bundle-budget.mjs`), run in CI immediately
after `npm run build`. It reads Turbopack's own `next build` diagnostics
(`.next/diagnostics/route-bundle-stats.json`, `firstLoadUncompressedJsBytes` per route) rather than
re-measuring anything itself, and fails the build if any route exceeds the budget or if the
diagnostics file is missing (a silently-skipped check would be worse than no check).

**Why 650KB:** at the time this budget was set, the heaviest route (`/decisions`) measured ~577KB
and the lightest (`/[section]`) ~527KB — all routes sit close together because they share the same
floor of React, the design system, and session/telemetry client code. 650KB gives roughly 13%
headroom above the heaviest observed route: tight enough to fail on an accidental heavy import
(e.g. a large formatting/date library pulled into a client component), loose enough to absorb
normal dependency version bumps without needing a budget change on every routine PR. A single
global ceiling was chosen over a per-route budget because the routes' sizes are dominated by the
shared floor, not route-specific code — a per-route budget would mostly just duplicate the same
number nine times.

## Web Vitals budget

**Budget:** no core route may report a `poor`-rated Core Web Vital
(`features/telemetry/web-vitals-core.ts`'s `WEB_VITAL_RATINGS`) during a fixtures-mode run. The
`good`/`needs-improvement`/`poor` thresholds themselves are Next.js's own standard classification
(`next/web-vitals`'s `useReportWebVitals`), which follows the published Core Web Vitals
thresholds — this project does not define its own thresholds, only enforces that ratings stay out
of the worst bucket.

**Enforcement:** `e2e/performance-budget.spec.ts` reuses the console's existing telemetry pipeline
rather than adding new measurement machinery: `features/telemetry/web-vitals-reporter.tsx` already
collects real CLS/FCP/INP/LCP/TTFB via `next/web-vitals` and reports each one's rating to
`POST /api/bff/telemetry/web-vitals` via `navigator.sendBeacon`. The Playwright test intercepts
that same endpoint while navigating the core routes (`/`, `/decisions`, `/policies`,
`/recoveries`, `/outbox`) and asserts none of the captured metrics come back `poor`. `INP` will
often simply not report at all in this test (it requires a user interaction to fire), which is
expected and not a failure — the test only asserts on metrics that *did* report.

**Why not assert `good` strictly:** headless CI environments have more timing variance than a real
user's machine. `poor` is deliberately a generous, low-flakiness bar that still catches an actual
regression (e.g. a route accidentally blocking on a slow synchronous computation before paint),
without the test becoming a source of unrelated CI flakiness.

## Related

- [Frontend architecture](architecture.md)
- Issue #216 — Establish and CI-enforce frontend performance, bundle, and Web Vitals budgets.
