# ADR 0011: JWT resource server with a local, per-boot issuer

- Status: Accepted
- Date: 2026-07-24

## Context

Every REST endpoint (policy lifecycle, recovery review, simulation, actuator metrics) was callable anonymously. Reviewer identity on recovery review was accepted as an arbitrary client-supplied string. AccountShield needs explicit trust boundaries between public clients, security operators, policy administrators, simulation analysts, and observability readers, without building a real identity provider (an explicit non-goal) or storing end-user passwords.

Two options were considered: a documented local API-key adapter, or a JWT resource server. The API-key adapter is simpler and needs no token-issuing story, but reads less like a production system. JWT resource-server authentication was chosen for a more realistic demonstration of the trust boundary, accepting that a token-issuing story is still required even without a real IdP.

## Decision

Use Spring Security's OAuth2 resource server support (`spring-boot-starter-oauth2-resource-server`) to validate bearer JWTs. The signing key pair is **generated fresh in memory on every application start** (`LocalJwtKeys`) rather than committed to the repository or backed by a real IdP — a leaked signing key is reusable forever, unlike a labeled local-only database password, so committing one (even "for local use only") is not an acceptable trade-off in a public repository.

Roles are carried in a `roles` JWT claim and mapped to Spring `ROLE_*` authorities. Five roles are recognized: `PROTECTION_CLIENT`, `SECURITY_OPERATOR`, `POLICY_ADMIN`, `SIMULATION_ANALYST`, `OBSERVABILITY_READER`. Endpoints are authorized by role via `HttpSecurity#authorizeHttpRequests`; anything not explicitly matched requires authentication (deny-by-default). Recovery-review reviewer identity is now derived from the authenticated principal's subject claim, never from the request body.

Because keys live only in memory, tokens do not survive an application restart, and there is no way to validate a token issued by a previous run — acceptable for local development and this portfolio's purposes, unacceptable for anything resembling production.

## Alternatives considered

### Documented local API-key adapter

Simpler, needs no token-issuing story, and was the initially proposed default in issue #19. Rejected in favor of JWT because a resource-server shape reads closer to how this trust boundary would actually be built in production, which better serves the project's demonstration goals.

### A real identity provider (Keycloak, Auth0, Cognito)

Rejected for now: "building a full identity provider" is an explicit non-goal of issue #19, and operating a real IdP is disproportionate to a single-instance portfolio backend. Revisit if the project ever needs session revocation or multi-instance token validation (see Revisit criteria).

## Token issuance

No runtime HTTP endpoint hands out tokens for arbitrary roles in every environment — that would defeat the boundary. `DevTokenController` (`POST /dev/tokens`) mints a token for a given subject and role set using the same in-memory key, but is registered **only** under the `local` Spring profile, mirroring the project's existing "simulated providers restricted to local/test" precedent. Automated tests sign tokens directly against the same key material in-process, without an HTTP round trip.

## Consequences

### Positive

- every sensitive endpoint now has an explicit, enforced trust boundary instead of relying on convention;
- reviewer identity can no longer be spoofed by the caller;
- authorization failures return the same stable RFC 9457 Problem Details shape as the rest of the API, including the request correlation ID, instead of Spring Security's default responses;
- no secret material is committed to the repository.

### Negative

- tokens are unusable across restarts, which is unusual outside local/demo contexts and must stay clearly documented;
- there is still no real identity provider, session revocation, or credential lifecycle — this ADR only establishes the trust-boundary shape, not production-grade identity;
- `DevTokenController` is a second thing (beyond the resource-server config itself) that must remain profile-gated correctly, or the boundary is compromised.

## Guardrails

- `DevTokenController` must remain `@Profile("local")`-only; it must never be reachable when the `local` profile is not active;
- signing keys are never persisted, logged, or checked into the repository;
- the `roles` claim is the only source of authority — no endpoint infers a role from anything else (URL shape, request body, etc.);
- authorization failures never include account, policy, or internal-state details in the public response.

## Revisit criteria

Revisit when the project moves toward a deployment that needs tokens to survive a restart, session revocation, multi-instance token validation, or integration with a real identity provider (Keycloak, Auth0, Cognito). At that point the local issuer should be replaced, not extended.

## References

- Issue #19 — Add authentication and role-based authorization to sensitive APIs.
- `SecurityConfig`, `LocalJwtKeys`, `DevTokenController`, `ProblemDetailAuthenticationEntryPoint`, `ProblemDetailAccessDeniedHandler`.
