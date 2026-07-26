# AccountShield server-only BFF

The frontend BFF is a narrow boundary inside the Next.js application. It is not a general-purpose proxy, does not replace Spring authorization, and does not introduce operator authentication before its contract exists.

## Dependency direction

```text
browser
  -> narrow Route Handler or Server Component
  -> server-only use-case service
  -> exact AccountShield read client method
  -> Spring API
```

Current read-only proof:

```text
GET /api/bff/runtime-status
  -> handleRuntimeStatusRequest
  -> RuntimeStatusService
  -> AccountShieldReadClient.getRuntimeHealth
  -> GET /actuator/health
```

The transport seam is intentionally isolated in `src/server/bff/runtime-status-core.ts`. When the stable OpenAPI problem catalog and generated client are available, the exact read method can be replaced without changing the browser route or view model. Until then, the client exposes one checked endpoint and no arbitrary URL, path, method, query, or header parameter.

## Server-only ownership

`src/server/bff/runtime-status.ts` imports `server-only`. Backend origins, transport configuration, future credentials, and service construction belong behind this boundary. Importing this module from a Client Component causes the Next.js build to fail.

Pure contracts, validation, redaction, and transport behavior are separated into testable modules, but browser-facing code must import only minimized view models or call approved Route Handlers.

## Route catalog

| Browser route | Method | Upstream operation | Purpose |
| --- | --- | --- | --- |
| `/api/bff/runtime-status` | `GET` | `GET /actuator/health` in live mode | Minimal availability and data-source proof |

No route accepts a destination URL or backend path. Adding a use case requires a new named service method, explicit request policy, minimized response model, tests, and documentation.

## Fixture and live selection

`ACCOUNTSHIELD_DATA_SOURCE` selects `fixtures` or `live` through the validated frontend environment contract.

- fixture mode returns a synthetic status without network access;
- live mode requires the server-only `ACCOUNTSHIELD_API_URL` and calls the exact health endpoint;
- live failures never fall back to fixtures;
- preview and production remain subject to the existing build/runtime parity gate.

The runtime status response contains only:

- `availability`;
- `source`;
- `checkedAt`;
- `correlationId`.

Actuator components, hostnames, database details, and raw upstream bodies are not serialized.

## Correlation IDs

The BFF accepts only `x-correlation-id` values matching the documented bounded character policy. Invalid, blank, or oversized values are discarded and replaced with a `bff_<uuid>` identifier.

Only the validated correlation ID is propagated upstream. Browser-supplied authorization, identity, role, scope, forwarding, tracing, cookie, and internal headers are not forwarded.

Every success and Problem Details response returns the effective correlation ID.

## Timeouts, aborts, and retries

The read client has a bounded timeout controlled by `ACCOUNTSHIELD_BFF_TIMEOUT_MS`. Caller aborts and internal timeouts map to the stable retryable `UPSTREAM_TIMEOUT` problem.

Network failures map to `UPSTREAM_UNAVAILABLE`. The foundation does not retry automatically. Retries may be introduced only for a named operation whose idempotency and authorization semantics are documented and tested.

Responses are bounded by `ACCOUNTSHIELD_BFF_MAX_RESPONSE_BYTES`; declared or actual oversized responses are rejected as malformed.

## Problem Details

Browser failures use `application/problem+json` and the stable frontend shape:

```json
{
  "type": "https://accountshield.dev/problems/upstream-timeout",
  "title": "The AccountShield service did not respond in time.",
  "status": 504,
  "code": "UPSTREAM_TIMEOUT",
  "correlationId": "bff_...",
  "retryable": true
}
```

The BFF does not expose upstream `detail`, exception messages, stack traces, internal hostnames, resource-existence clues, or arbitrary backend problem fields.

Current typed mappings include authentication, forbidden operations, timeout, unavailable service, malformed response, unsupported method, unsupported media type, oversized payload, invalid request, and internal error. Issue #36 remains responsible for the complete versioned backend problem catalog; adding its stable codes will extend these explicit mappings rather than forwarding raw responses.

## Request policy

Shared request helpers enforce:

- explicit allowed methods;
- explicit content types for body-bearing operations;
- declared and actual body-size limits;
- JSON object parsing only;
- stable method, media-type, payload, and validation errors.

The current runtime-status route is bodyless and GET-only. POST, PUT, PATCH, and DELETE return 405 with `Allow: GET`.

## Logging and redaction

Structured BFF logs contain an event name, correlation ID, and redacted context. Key-based redaction covers authorization, cookies, tokens, secrets, passwords, credentials, challenges, raw values, and account/user/contact identifiers. Bearer and JWT-like values embedded in strings are also removed.

Do not log Request or Response objects, full URLs with query strings, raw request bodies, upstream bodies, headers, Client Component props, or caught exception messages.

## Authorization and caching

Spring authorization remains authoritative. A backend 401 or 403 remains an authentication or forbidden frontend problem and is never converted into success, fixtures, or an existence-sensitive message.

BFF HTML and JSON responses use `private, no-store, max-age=0, must-revalidate`. Future caching must be scoped to verified operator identity and authorization context; shared cross-operator caches are forbidden by default.

## Testing contract

Unit tests cover:

- correlation validation and generation;
- unsupported methods and media types;
- declared and actual oversized requests;
- log redaction;
- exact upstream path and header allowlist;
- backend denial and RFC 9457-shaped responses;
- timeout, caller abort, network failure, malformed and oversized responses;
- fixture/live view-model minimization.

Playwright covers the production Route Handler for fixture success, invalid correlation replacement, no-store behavior, minimal serialization, and 405 Problem Details.

## Extension checklist

A new BFF use case must:

1. use a named route and named service method;
2. expose no arbitrary upstream path or destination;
3. validate method, content type, and size;
4. propagate only explicitly allowed headers;
5. define timeout, abort, retry, and idempotency behavior;
6. normalize backend errors to stable safe problems;
7. minimize response fields before browser serialization;
8. document authorization and cache scope;
9. add redaction, unit, integration, and browser tests;
10. update this route catalog.

## Non-goals

- generic reverse proxying;
- operator login, access-token, or refresh-token implementation;
- localStorage or sessionStorage credentials;
- privileged mutations;
- client-side backend calls;
- replacing backend authorization;
- a separately deployed BFF service.
