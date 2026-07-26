# AccountShield frontend OpenAPI client

The frontend uses a versioned OpenAPI 3.1 snapshot and deterministic generated TypeScript artifacts to prevent handwritten drift between Spring contracts and the Next.js BFF.

## Authoritative input

The frontend generation input is:

```text
openapi/accountshield.openapi.json
```

It is a reviewed snapshot of the published Springdoc contract. It intentionally contains no top-level `servers` section, environment hostname, credential, token, or deployment-specific value. Runtime origins remain exclusively in the server-only environment contract.

The initial snapshot covers the currently published `POST /api/v1/protection-decisions` operation, request and response DTOs, enums, bearer security declaration, RFC 9457 Problem Details shape, and the stable problem codes already emitted by the backend.

Issue #52 remains responsible for the complete backend-side versioning and compatibility policy. Until that gate is finished, changes to this snapshot require direct comparison with the Spring controllers, DTOs, enums, and problem handler.

## Generator

The repository-owned generator is:

```text
scripts/generate-openapi-client.mjs
accountshield-openapi-generator@1.0.0
```

It uses Node.js built-ins only, so generation adds no runtime package, transitive dependency, remote download, or supply-chain source.

Commands:

```bash
npm run openapi:generate
npm run openapi:check
```

`openapi:generate` writes deterministic files under `src/generated/accountshield`. `openapi:check` recomputes the expected output in memory and fails when committed generated files are missing or stale.

The frontend CI runs `openapi:check` before lint, typecheck, tests, and build. A contract change without regenerated artifacts therefore fails the pull request.

## Generated output

Generated files contain a banner with:

- generator name and version;
- source path;
- source SHA-256;
- regeneration command;
- do-not-edit warning.

The generator currently supports the reviewed subset used by AccountShield: component references, object schemas, nullable object and primitive types, arrays, primitive values, string enums, request bodies, successful JSON responses, and named operations.

Unsupported schema constructs fail generation instead of silently degrading to `any`.

## No environment or credential generation

The generator rejects any OpenAPI document containing a top-level `servers` field. The output contains only relative operation paths and transport interfaces.

Generated files must never contain:

- `http://` or `https://` origins;
- localhost or Compose service hostnames;
- `ACCOUNTSHIELD_API_URL` or other runtime variables;
- Authorization header values, bearer tokens, cookies, or browser storage logic;
- embedded credentials or secret defaults.

A unit test scans the committed artifacts and verifies the source hash and forbidden markers.

## Consumption boundary

Generated code is infrastructure, not an application-facing API.

Allowed dependency direction:

```text
Spring OpenAPI snapshot
  -> generated types and operations
  -> handwritten src/server/bff adapter
  -> stable BFF view model or use-case service
  -> page/feature component
```

Pages, features, and design-system components cannot import `@/generated/accountshield/*`; ESLint enforces this. The official server import surface is `src/server/bff/protection-decision-contract.ts`, which imports `server-only`.

The pure `*-core.ts` adapter remains independently testable but must not be imported by Client Components.

## Stable adapters

`ProtectionDecisionContractClient` invokes the generated exact operation and adapts the response into `ProtectionDecisionView`.

The adapter deliberately removes transport and backend-only fields, including request IDs, recovery authorization IDs, challenge IDs, arbitrary additional fields, raw Problem Details, and internal backend context.

Feature code receives only the fields needed by the operator view:

- decision identifier;
- normalized outcome and risk band;
- risk score and algorithm version;
- policy key/version;
- bounded reason entries;
- decision timestamp;
- minimized challenge type and expiry.

## Additive enum compatibility

Generated enum types are extensible string types rather than closed unions. Each enum also exports `KnownValues` and a known-value union.

Handwritten adapters compare values against `KnownValues`. An additive backend enum value therefore becomes an explicit `unknown` presentation state instead of crashing rendering, producing an impossible exhaustive branch, or being silently mislabeled.

Unknown values must remain visible as unknown/degraded state until a reviewed mapping is added. They must not be coerced to the closest known semantic value.

## Problem Details

The generated schema represents backend RFC 9457 fields, including optional backend `detail`. The handwritten adapter never forwards that object to the browser.

Known backend codes currently map to stable frontend errors:

| Backend code | Frontend code | HTTP status | Retryable |
| --- | --- | ---: | --- |
| `INVALID_PROTECTION_REQUEST` | `INVALID_REQUEST` | 400 | no |
| `ACTIVE_POLICY_UNAVAILABLE` | `UPSTREAM_UNAVAILABLE` | 503 | yes |
| `IDEMPOTENCY_CONFLICT` | `CONFLICT` | 409 | no |
| `RATE_LIMIT_EXCEEDED` | `RATE_LIMITED` | 429 | yes |

Unknown future problem codes use a controlled status-based fallback. Backend title, detail, instance, internal hostname, resource existence, account identifier, exception, and stack information are discarded.

Issue #36 remains responsible for completing and versioning the backend problem catalog. New stable backend codes must be added to the OpenAPI snapshot, regenerated output, adapter mapping, and tests together.

## Updating the contract

When the Spring contract changes:

1. obtain or review the published Springdoc OpenAPI output;
2. update `openapi/accountshield.openapi.json`, removing environment-specific `servers` values;
3. compare DTO required fields, nullability, formats, enums, operation IDs, status codes, security declarations, and Problem Details;
4. run `npm run openapi:generate`;
5. review the generated diff rather than blindly accepting it;
6. update handwritten adapters for semantic changes or new known enum/problem values;
7. run `npm run openapi:check`, lint, typecheck, unit tests, and build;
8. record whether the change is additive, compatible, or breaking under the #52 policy.

Generated files are committed so pull requests expose contract drift and remain buildable without a running backend.

## Review requirements

A generated-client change must verify:

- the snapshot matches published Spring behavior;
- no host or credential entered the contract or output;
- required/optional and nullable fields are faithful;
- operation method, path, and success status are correct;
- additive enums have explicit unknown behavior;
- Problem Details do not cross the BFF boundary raw;
- handwritten adapters minimize browser-visible data;
- generation and drift checks pass from a clean checkout.
