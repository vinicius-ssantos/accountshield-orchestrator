# Executable frontend architecture boundaries

The frontend architecture is enforced by repository-owned Node.js checks, not only by convention. The checker scans the TypeScript and JavaScript dependency graph without executing application modules or downloading additional tooling.

Run locally:

```bash
npm run architecture:test
npm run architecture:check
```

`architecture:test` creates isolated synthetic projects and proves that every rule detects its target violation. `architecture:check` analyzes the real frontend. CI runs both commands after OpenAPI drift validation and before lint, typecheck, tests, and build.

## Dependency direction

The intended direction is:

```text
app composition and Route Handlers
  -> feature contracts and data-source boundaries
  -> server-only BFF adapters
  -> generated transport contracts

app and features
  -> design system

feature get-data-source
  -> same-feature fixtures or live adapter
```

The following reverse directions are forbidden:

- design system to app, features, server, or generated transport;
- feature to app, server internals, or generated transport;
- server to app, features, or presentation components;
- generated output to handwritten layers;
- one feature directly to another feature.

Cross-feature composition belongs in the app layer or in a reviewed shared contract extracted from both features.

## Configuration

`architecture.config.mjs` is the reviewed source of architecture policy:

- `publicEnvAllowlist` lists the only browser-visible environment variables;
- `generatedImportAllowedPrefixes` lists handwritten infrastructure locations permitted to wrap generated contracts;
- `readOnlyScopes` lists roots whose complete internal dependency closure must remain free of upstream mutation operations;
- `exceptions` contains narrow, temporary, owned exceptions.

The initial public allowlist contains only `NEXT_PUBLIC_APP_ENV`. Backend origins, credentials, data-source modes, transport limits, and security configuration remain server-only.

The initial read-only scopes are the runtime-status BFF route and service plus the decisions feature. Exporting POST, PUT, PATCH, or DELETE from a Route Handler solely to return a stable 405 is permitted; the checker rejects actual generated mutation clients or transport objects declaring unsafe methods inside the read-only dependency closure.

## Rule catalog

| Rule | Enforced boundary |
| --- | --- |
| `ARCH001` | A Client Component cannot import `server-only`, `src/server`, a transitive server-only module, backend origins, or non-public environment configuration. |
| `ARCH002` | Generated transport code can be imported only by configured handwritten BFF adapters. |
| `ARCH003` | Pages and presentation code cannot import fixtures directly; fixture selection belongs in the same feature's `get-data-source` module. |
| `ARCH004` | Every `NEXT_PUBLIC_*` usage and declaration must be explicitly allowlisted. |
| `ARCH005` | App, feature, and design-system code cannot construct raw backend requests or use backend origins directly. |
| `ARCH006` | BFF code cannot accept arbitrary destinations or backend paths, use catch-all proxy routes, or derive an upstream URL from request data. |
| `ARCH007` | A configured read-only scope cannot depend on a generated operation client or transport object declaring POST, PUT, PATCH, or DELETE. |
| `ARCH008` | Layer imports must follow the allowed dependency direction. |
| `ARCH009` | Feature modules cannot import a different feature directly. |
| `ARCH010` | Internal source dependencies must remain acyclic. |
| `ARCH011` | Architecture exceptions must be narrow, current, owned, justified, and linked to tracked work. |

Each violation reports the rule ID, exact file and line, target when available, and a remediation path. `node scripts/check-architecture.mjs --json` emits machine-readable output for future reporting integrations.

## Server-only classification

A source module is classified as server-only when it:

- lives under `src/server`;
- imports `server-only`;
- reads a non-`NEXT_PUBLIC_*` environment variable; or
- imports another module already classified as server-only.

This transitive classification prevents a Client Component from bypassing the boundary through an intermediate re-export or alias.

## Generated contracts

Generated files under `src/generated` are infrastructure artifacts. Presentation and feature modules must never import them directly. The generated-client boundary established by #67 remains:

```text
generated operation/type
  -> handwritten src/server/bff adapter
  -> minimized stable view model
  -> feature or page
```

Generated models are transport descriptions, not authorization, validation, or browser-safe serialization guarantees.

## Fixture boundaries

A feature may contain deterministic fixtures, but only its `get-data-source` composition module may select them in production source code. Tests may import fixtures directly. Pages receive a data source or stable feature result rather than choosing fixtures themselves.

This preserves the ability to switch fixture/live behavior without spreading environment checks or mock-specific imports through presentation code.

## Generic proxy prevention

BFF routes must be named for a use case and bind to a fixed upstream operation. The checker rejects common proxy shapes, including:

- catch-all API routes such as `[...path]`;
- `url`, `path`, `target`, `destination`, `upstream`, or `backend` request parameters used as destinations;
- request-derived values passed to `fetch` or `new URL`;
- generic destination/path parameter names in BFF modules.

Adding a route requires an exact method, exact upstream operation, explicit request policy, authorization behavior, timeout, abort handling, response minimization, safe error mapping, and tests.

## Exceptions

Architecture exceptions are intentionally expensive and fail closed. An entry in `architecture.config.mjs` must contain:

```js
{
  ruleId: "ARCH001",
  path: "src/app/exact-file.tsx",
  target: "src/server/exact-module.ts", // optional, but exact when present
  rationale: "Specific technical reason with enough detail for review.",
  owner: "frontend-platform",
  issue: "#123",
  revisitOn: "2026-10-01",
}
```

Requirements:

- exact source path; wildcard paths are forbidden;
- exact target when target scoping is used;
- valid rule ID other than `ARCH011`;
- rationale of at least 20 characters;
- named owner;
- GitHub issue reference or URL;
- future `YYYY-MM-DD` revisit date;
- an actual currently detected violation.

Expired, malformed, wildcard, unknown, or stale exceptions fail as `ARCH011`. A stale exception is one that no longer matches a current violation, preventing permanent bypass entries from accumulating.

## Changing a boundary

A boundary change requires:

1. a GitHub issue describing the dependency or security need;
2. review against frontend ADRs and the BFF/security model;
3. an ADR update or new ADR when the allowed direction or trust boundary changes;
4. a rule-engine self-test demonstrating the intended new behavior;
5. documentation and configuration changes in the same pull request;
6. green architecture checks, lint, typecheck, tests, build, browser tests, and container smoke.

Do not weaken a rule merely to make a feature implementation pass. Prefer moving composition to the correct layer, introducing a narrow adapter, or extracting a stable shared contract.

## Limitations and review

The checker uses static import and source-pattern analysis. It is designed to catch high-value architectural drift with clear diagnostics and no new supply-chain dependency; it does not replace TypeScript, Next.js server/client enforcement, security review, or human architectural review.

When syntax or framework patterns evolve, update the synthetic regression cases first, then update the analyzer while preserving fail-closed behavior for newly introduced violations.
