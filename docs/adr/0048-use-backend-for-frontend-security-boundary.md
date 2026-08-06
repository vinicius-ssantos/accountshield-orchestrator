# ADR 0013: Use a backend-for-frontend security boundary

- Status: Accepted
- Date: 2026-07-23

## Context

The AccountShield console will eventually authenticate operators and access sensitive investigation and administrative capabilities. Letting browser code manage long-lived backend credentials or call privileged Spring endpoints directly would expand the attack surface, complicate token rotation, and make it easier to leak credentials through storage, logs, browser extensions, analytics, or client-side errors.

The frontend also needs one place to normalize backend errors, propagate correlation context, enforce browser-facing response minimization, and evolve session behavior without coupling UI components to identity-provider details.

## Decision

Use a backend-for-frontend boundary for authenticated browser access.

Next.js server-side handlers, server actions, or an equivalent dedicated BFF layer will hold the browser-facing session and communicate with AccountShield backend APIs. The browser will receive only the minimum data required to render the authorized operator experience.

The exact identity provider is intentionally undecided, but the security boundary is fixed:

- long-lived access and refresh credentials are not exposed to browser JavaScript;
- tokens are not stored in `localStorage` or `sessionStorage`;
- browser sessions use secure, `HttpOnly`, appropriately scoped cookies when authentication is introduced;
- privileged backend APIs do not rely on browser-supplied role or identity claims without server verification;
- direct browser-to-backend access is not the default architecture for authenticated console operations.

## Responsibilities of the BFF boundary

- establish and refresh the operator session;
- propagate authenticated identity and authorized scopes to the backend;
- preserve or create correlation identifiers;
- normalize RFC 9457 Problem Details for UI consumption;
- remove fields the browser does not need;
- apply browser-specific CSRF and origin protections;
- enforce request-size, method, and content-type constraints;
- avoid logging secrets, tokens, challenge material, or raw sensitive identifiers;
- terminate the browser session safely on revocation or refresh failure.

## Consequences

### Positive

- browser JavaScript does not manage reusable backend credentials;
- identity-provider integration is isolated from page components;
- response minimization and error normalization have a clear boundary;
- session revocation and refresh behavior can evolve independently of UI code;
- direct access to privileged backend endpoints is reduced;
- correlation and audit context can be propagated consistently.

### Negative

- another runtime boundary must be operated and monitored;
- BFF outages can make the console unavailable even when the backend is healthy;
- caching and streaming decisions require additional care;
- server-side code must avoid becoming a second domain layer or duplicating backend authorization;
- local development requires coordinated frontend and backend configuration.

## Guardrails

- backend authorization remains authoritative for every protected resource and command;
- the BFF must not translate a denied backend operation into success;
- session cookies must use `Secure` in non-local environments and must be `HttpOnly`;
- state-changing browser requests require CSRF protection appropriate to the selected session model;
- secrets and raw tokens must never be returned in serialized page props, client-component props, logs, analytics, or error messages;
- BFF endpoints expose narrow use-case contracts rather than generic credentialed proxy behavior;
- forwarding arbitrary target URLs or arbitrary backend paths is prohibited;
- operator logout and credential revocation must invalidate the effective session server-side.

## Alternatives considered

### Store access tokens in localStorage and call the backend directly

Rejected because browser-accessible persistent tokens increase exposure to XSS, extensions, accidental logging, and token lifecycle mistakes.

### Store tokens in browser memory only

Rejected as the primary model because refresh, multi-tab behavior, navigation, and server rendering remain complex, while privileged APIs are still directly exposed to the browser.

### Implement authentication only in the Spring backend with no BFF

Not selected as the default browser architecture. It may remain suitable for service clients, but the operator console benefits from a dedicated browser-session and response-minimization boundary.

## Revisit criteria

Revisit the deployment shape when:

- the frontend is hosted by a platform that provides an equivalent trusted server-side session boundary;
- an organization-wide API gateway supplies the same session, CSRF, minimization, and token-confidentiality guarantees;
- the console becomes a purely static client with no authenticated or sensitive capability.

The prohibition on browser-accessible long-lived credentials remains unless a new ADR documents equivalent or stronger protections.
