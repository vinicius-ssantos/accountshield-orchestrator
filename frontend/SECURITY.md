# AccountShield frontend security baseline

This document defines the browser, cache, and environment configuration baseline for the AccountShield Security Operations Console.

## Response headers

All application pages and Route Handlers receive:

| Header | Policy |
| --- | --- |
| `Content-Security-Policy` | Per-request nonce, self-hosted resources, no objects, no framing, no unrestricted script execution |
| `Cache-Control` | `private, no-store, max-age=0, must-revalidate` for console and BFF responses |
| `X-Frame-Options` | `DENY` as a legacy complement to CSP `frame-ancestors 'none'` |
| `X-Content-Type-Options` | `nosniff` |
| `Referrer-Policy` | `no-referrer` |
| `Permissions-Policy` | Camera, microphone, geolocation, payment, USB, and browsing topics disabled |
| `Cross-Origin-Opener-Policy` | `same-origin` |
| `Cross-Origin-Resource-Policy` | `same-origin` |
| `Origin-Agent-Cluster` | `?1` |
| `X-Robots-Tag` | `noindex, nofollow, noarchive` |

Hashed Next.js static assets retain their framework-managed immutable cache behavior. The no-store policy applies to HTML, Route Handlers, and the future BFF surface.

## Content Security Policy

`src/proxy.ts` generates a cryptographically random nonce for every request and places the same CSP on the request and response. Next.js reads the request CSP and applies that nonce to framework scripts.

The production policy:

- allows scripts from the current application only when authorized by the per-request nonce;
- uses `strict-dynamic` so nonce-authorized framework loaders can load their traced chunks;
- does not include `script-src 'unsafe-inline'`;
- does not include `unsafe-eval` outside `next dev`;
- denies plugins through `object-src 'none'`;
- denies embedding through `frame-ancestors 'none'`;
- denies base-tag rewriting through `base-uri 'none'`;
- limits forms to the same origin;
- upgrades insecure requests only in preview and production.

### Temporary style exception

`style-src 'unsafe-inline'` remains temporarily enabled for framework and component style compatibility. It does not authorize inline scripts. Revisit this exception when the design system in #76 has stable nonce-aware styling and the browser suite can prove its removal without regressions.

## Transport security

- local, test, and CI HTTP responses do not emit HSTS;
- preview emits `max-age=31536000` without subdomain or preload directives;
- production emits `max-age=63072000; includeSubDomains; preload`.

Only deploy the production policy on a domain whose subdomains are permanently HTTPS-compatible and whose preload registration is intentional.

## Environment contract

| Variable | Visibility | Rules |
| --- | --- | --- |
| `NEXT_PUBLIC_APP_ENV` | Browser-visible | Non-secret label: `local`, `test`, `ci`, `preview`, or `production` |
| `ACCOUNTSHIELD_DATA_SOURCE` | Server-only | `fixtures` or `live`; preview and production require `live` |
| `ACCOUNTSHIELD_API_URL` | Server-only | Absolute HTTP(S) origin, no credentials, path, query, or fragment |
| `ACCOUNTSHIELD_BUILD_APP_ENV` | Image-internal | Records the environment used to build the client bundle |
| `ACCOUNTSHIELD_BUILD_DATA_SOURCE` | Image-internal | Records the data mode used to build the image |

The API origin must never be renamed with a `NEXT_PUBLIC_` prefix, added to page props, rendered into HTML, or accepted from browser input. Variables whose public names appear secret-bearing are rejected.

The standalone container compares build and runtime markers. A fixture-built image cannot be promoted as a live or production image by changing runtime variables after the build.

## Fail-fast behavior

Configuration is validated:

1. during `next build`, without requiring the server-only API URL to be embedded in the image;
2. before the Next.js process starts through `scripts/start.mjs`, which is the authoritative fail-fast gate for standalone and container runtime;
3. during Node.js server initialization through `src/instrumentation.ts` as defense in depth;
4. on routed requests through `src/proxy.ts` as a final defense-in-depth check.

The launcher must remain the container entrypoint. Next.js may log an instrumentation-hook exception without terminating the server, so instrumentation alone is not treated as a startup barrier.

Live runtime requires `ACCOUNTSHIELD_API_URL`. Production rejects loopback API origins. Preview or production fixture mode aborts rather than silently presenting synthetic data as live data.

## Error and indexing behavior

- production Next.js responses must not render stack traces, environment values, internal hostnames, tokens, or unrestricted upstream payloads;
- metadata and `X-Robots-Tag` both prevent indexing;
- health output remains minimal and non-cacheable;
- test artifacts must use synthetic identifiers and bounded retention.

## Verification

Vitest covers environment validation, CSP construction, HSTS selection, and static headers. Playwright verifies representative HTML and Route Handler responses, nonce rotation, absence of the server-only API sentinel in HTML and client chunks, and blocking of an unauthorized inline script. The container smoke gate also proves that build/runtime mode mismatches terminate the process.

## Revisit criteria

Review this baseline when any of the following changes:

- #66 introduces BFF routes or session cookies;
- #76 changes the styling mechanism;
- third-party scripts, fonts, images, telemetry, or WebSocket endpoints are introduced;
- the console moves behind a CDN, reverse proxy, or a different public origin;
- production domains or HSTS preload ownership changes.
