# ADR 0052: BFF-managed opaque session cookie with double-submit CSRF

- Status: Accepted
- Date: 2026-07-31

## Context

Issue #78 ("Implement the secure operator session through the Next.js BFF") requires a
browser-facing operator session with no reusable backend credential exposed to browser
JavaScript, backed by the newly added demo credential adapter (`POST /auth/session-tokens`,
ADR 0046 on the backend). The BFF foundation (ADR 0048) already establishes that the browser
never talks to the Spring API directly; this ADR decides how the *session itself* is represented
between the browser and the BFF.

## Decision

The session cookie carries an **opaque, HMAC-signed session ID only** -- never the backend JWT.
The JWT returned by `/auth/session-tokens` is held server-side, in an in-memory
`Map<sessionId, SessionRecord>` (`frontend/src/server/bff/session/session-store.ts`), for the
lifetime of the Next.js server process. This is a deliberate, documented single-instance
limitation with the same precedent as the backend's `LocalJwtKeys` (ADR 0011) and in-memory rate
limiter (ADR 0008): sessions do not survive a server restart or scale-out to multiple instances.

Cookie shape: `__Host-as_session` in `productionLike` environments (`HttpOnly`, `Secure`,
`SameSite=Lax`, `Path=/`, no `Domain`), falling back to a plain `as_session` name locally since
`__Host-` requires HTTPS. The cookie value is `<sessionId>.<hmac>`, HMAC-SHA256 over the session
ID keyed by `ACCOUNTSHIELD_SESSION_SECRET`, verified via Node's built-in `crypto` (no new
dependency). Absolute session lifetime is 8 hours; inactivity timeout is 20 minutes; both are
enforced server-side against the store record on every request and never trusted from the
cookie's own `Max-Age`.

CSRF protection is a double-submit cookie: a second, non-`HttpOnly` cookie (`as_csrf`) carries an
HMAC-derived token the browser echoes back via a custom `x-as-csrf-token` header on every
mutating (non-GET/HEAD) BFF request; the server verifies the echoed value against the session
record's stored `csrfSecret`. `SameSite=Lax` was chosen over `Strict` to avoid breaking the first
navigation into the app after a login redirect; combined with the CSRF token and an
Origin/`Sec-Fetch-Site` check (`frontend/src/server/bff/session/csrf.ts`), this covers the same
threat model as `Strict` alone without the navigation cost.

The backend's own JWT TTL (15 minutes, ADR 0046) is shorter than the BFF's inactivity window, so
`POST /auth/session-tokens/refresh` lets the BFF silently mint a fresh backend token without
disturbing the browser-facing session cookie.

## Alternatives considered

### Store the backend JWT directly in the cookie (encrypted or otherwise)

Rejected: revocation would still need a server-side denylist to be instant (an encrypted
self-contained cookie can only be invalidated by checking a list anyway), so a server-side
session store is strictly simpler and is already required regardless. It would also make the
cookie considerably larger for no benefit.

### Rely on `SameSite=Strict` alone, no CSRF token

Rejected: acceptable in isolation, but the incremental cost of the double-submit token is small
and it is defense in depth against any future same-site subdomain or browser-implementation edge
case, which is cheap to add since HMAC signing already exists for the session cookie itself.

### A synchronizer token (per-form server-side CSRF state)

Rejected: would need additional per-request server state beyond the session record that already
exists; the double-submit design gets equivalent protection from data already being stored.

## Consequences

### Positive

- no reusable backend credential is ever exposed to browser JavaScript or written to any
  browser-readable storage;
- revocation (logout) is immediate: deleting the store entry, not waiting on a token to expire;
- CSRF and cross-origin mutation are rejected before reaching any BFF route handler logic.

### Negative

- sessions are lost on server restart or when running more than one Next.js instance -- a
  documented limitation, not a hidden one;
- an operator who is logged in when the BFF process restarts must sign in again;
- there is no cross-tab session sharing beyond what cookies naturally provide plus the
  `BroadcastChannel`-based logout notification -- login itself is not broadcast, only logout.

## Guardrails

- the backend JWT is never included in any BFF response body, browser-visible header, URL, or
  log (enforced by `frontend/src/server/bff/session/session-core.ts`'s response-body builders,
  which only ever expose `subject`/`roles`/`expiresAt`, and proven by test);
- `ACCOUNTSHIELD_SESSION_SECRET` is a server-only environment variable, never `NEXT_PUBLIC_*`,
  required to be at least 32 characters in `productionLike` environments;
- absolute and inactivity expiry are enforced against the server-side store record on every
  request, never against client-supplied cookie metadata;
- every mutating BFF request is rejected unless both the CSRF header matches and the request's
  Origin/`Sec-Fetch-Site` is same-origin.

## Migration/compatibility implications

None -- this is new, additive infrastructure. The 8 pre-existing BFF feature routes were updated
in a follow-up change to prefer the session-derived Bearer token over the
`ACCOUNTSHIELD_OPERATOR_TOKEN` environment variable, which remains available only as an explicit,
non-`productionLike` fallback for fixtures/local-dev convenience.

## Revisit criteria

Revisit if the console needs to run as more than one Next.js instance (the in-memory store would
need to move to a shared backing store), or if a real identity provider eventually replaces the
demo credential adapter on the backend (ADR 0046) -- the session-cookie/CSRF design here should
still apply largely unchanged, since it never assumed anything about how the backend token was
obtained.

## References

- Issue #78 -- Implement the secure operator session through the Next.js BFF.
- Backend ADR 0046 -- config-seeded demo operator credential adapter (issues #189/#190).
- ADR 0048 -- backend-for-frontend security boundary.
- `frontend/src/server/bff/session/` (crypto, store, core, csrf, session.ts, require-session.ts),
  `frontend/src/proxy.ts`, `frontend/src/features/session/`.
