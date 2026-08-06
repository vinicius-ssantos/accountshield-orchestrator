# ADR 0046: Config-seeded demo operator credential adapter for BFF session login

- Status: Accepted
- Date: 2026-07-31

## Context

Issue #78 ("Implement the secure operator session through the Next.js BFF") needs *something*
real to verify an operator's identity against before the BFF can establish a session. The
backend's only JWT issuer today is `DevTokenController` (`POST /dev/tokens`): it is gated to
`@Profile("local")` and mints a token for **any** subject and role list with zero credential
check — a deliberate demo stand-in from ADR 0011, never intended as a login endpoint. Outside
the `local` profile nothing issues tokens at all, so a live/demo deployment of the operator
console has no way to authenticate anyone.

ADR 0011 explicitly anticipated this gap in its own Revisit criteria: "revisit when the project
moves toward a deployment that needs tokens to survive a restart, session revocation... At that
point the local issuer should be replaced, not extended." This ADR fulfills that revisit: it
adds a real (if narrow) credential check without turning the project into an identity provider,
which remains an explicit non-goal of epic #41.

## Decision

Add `POST /auth/session-tokens`, verifying `{username, password}` against a small, fixed set of
named demo operator personas (`operator-1`, `analyst-1`, `admin-1`, `reader-1` — one per
human-facing role: `SECURITY_OPERATOR`, `SIMULATION_ANALYST`, `POLICY_ADMIN`,
`OBSERVABILITY_READER`; `PROTECTION_CLIENT` is excluded as a machine-to-machine role, not an
operator login persona). Passwords are bcrypt-hashed (Spring Security's `BCryptPasswordEncoder`,
already a dependency via `spring-boot-starter-security` — no new library) and the persona list
is config-seeded via `@ConfigurationProperties(prefix = "accountshield.auth.demo-credentials")`
in `application.yml`. On success, the endpoint signs a JWT through the *existing*
`LocalJwtKeys` signer — the same in-memory, per-boot key `DevTokenController` already uses, so
no new signing/verification story is introduced.

The endpoint is **not** gated to `@Profile("local")` — it is reachable in every profile, gated
instead behind `accountshield.auth.demo-credentials.enabled` (default `true`) so it can be
disabled with a single flag without removing the persona list. `DevTokenController`/`/dev/tokens`
is unchanged and remains `local`-only, kept purely for role-testing convenience.

A companion `POST /auth/session-tokens/refresh` reissues a fresh token for the same
subject/roles. It requires no bespoke token parsing: it sits behind the same oauth2
resource-server filter chain as every other authenticated endpoint (`.authenticated()`, not
`permitAll()`), so the caller's current bearer token is validated (signature and expiry) by
existing, already-tested infrastructure before the handler ever runs.

Failed logins — wrong password for a known username, or an unknown username entirely — return
the identical generic 401 (`INVALID_CREDENTIALS`). `DemoOperatorCredentialVerifier` always runs
one `PasswordEncoder.matches()` comparison per attempt, against the real persona's hash when the
username is known or a fixed dummy hash when it is not, so a failed attempt's cost does not
reveal whether the username exists.

The persona list is config-only — no database table. A fixed set of named personas has no
create/update/delete lifecycle, matching `LocalJwtKeys`'s existing in-memory-only precedent; a
table would add a migration and an admin surface for a list that never changes at runtime. The
committed bcrypt hashes are not sensitive: the demo credentials are meant to be publicly
documented (README, demo script), not confidential.

## Alternatives considered

### Have the BFF call `/dev/tokens` directly

Rejected: `/dev/tokens` performs no credential check at all, so this would mean anyone who can
reach the backend could mint a session as any role, defeating the entire purpose of issue #78.

### A real identity provider (Keycloak, Auth0, Cognito)

Rejected for the same reason ADR 0011 rejected it: "building a full identity provider" is an
explicit non-goal of the epic, and operating a real IdP is disproportionate to a single-instance
portfolio backend.

### A database-backed credential table

Rejected: the persona set is fixed and has no lifecycle operations (no signup, no self-service
password change, no admin CRUD in scope). A migration and table would add surface area without
adding capability. Revisit if the project ever needs more than a handful of personas or
persisted, per-account revocation.

## Consequences

### Positive

- closes the "backend has no credential-verifying login" gap without violating the "no real
  identity provider" non-goal;
- small, auditable, and reuses existing infrastructure end to end (`BCryptPasswordEncoder` from
  an existing dependency, `LocalJwtKeys` for signing, the existing oauth2 resource-server filter
  chain for refresh validation, the existing Problem Details/`RestControllerAdvice` pattern for
  error shapes);
- `/dev/tokens` and its existing role-testing usage are untouched.

### Negative

- still not real user management — a fixed, small persona list only, with no signup, password
  reset, or per-account revocation;
- the demo password material is intentionally public knowledge and must be documented as such
  everywhere it appears (backend README, frontend login page copy) so nobody mistakes this for
  real authentication in a real deployment;
- no rate-limiting or lockout on login attempts in this issue — a known, explicitly out-of-scope
  limitation (see Revisit criteria).

## Guardrails

- the endpoint never accepts arbitrary role claims from the caller — roles come only from the
  matched persona's config entry;
- `DemoOperatorCredentialVerifier.verify()` always performs exactly one `PasswordEncoder.matches()`
  call per attempt, against a real-or-dummy hash, so response cost does not vary by username
  existence;
- the `PasswordEncoder` bean is bcrypt only; no `NoOpPasswordEncoder` or plaintext fallback is
  ever wired, including in tests;
- token TTL stays short (15 minutes) and bounded;
- `/dev/tokens` remains `@Profile("local")`-only and unaffected by this change.

## Revisit criteria

Revisit if the project ever needs real user signup or self-service password reset, more than a
handful of personas, rate-limiting/lockout on login attempts, or persisted revocation tied to
individual demo accounts (today's revocation is session-cookie-level in the BFF, per issue #78,
not backend-credential-level).

## References

- Issue #189 — Add config-seeded demo operator credential verification for BFF session login.
- Issue #78 — Implement the secure operator session through the Next.js BFF.
- ADR 0011 — JWT resource server with a local, per-boot issuer (this ADR fulfills its Revisit
  criteria).
- `SecurityConfig`, `LocalJwtKeys`, `DemoOperatorSessionController`,
  `DemoOperatorCredentialVerifier`, `DemoOperatorCredentialProperties`,
  `DemoOperatorSessionProblemHandler`.
