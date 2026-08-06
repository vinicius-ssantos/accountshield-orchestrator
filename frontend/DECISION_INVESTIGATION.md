# Decision investigation console

This console combines the decision queue delivered by frontend #69 with the explainable timeline and provenance view from frontend #70. The detail view consumes the authorized backend contract delivered by #171/#172.

## Data flows

```text
browser /decisions
  -> POST /api/bff/decision-search
  -> server-only validation and telemetry
  -> generated searchDecisionInvestigations operation
  -> POST /api/v1/operator/decisions/search
  -> privacy-minimized DecisionSearchResponse
```

```text
selected queue row
  -> POST /api/bff/decision-timeline
  -> server-only UUID validation and response allowlist
  -> generated investigateDecision operation
  -> POST /api/v1/operator/decisions/investigate
  -> privacy-minimized DecisionTimelineResponse
```

The browser never receives `ACCOUNTSHIELD_OPERATOR_TOKEN` and never calls the Spring API origin directly.

## URL privacy

Search criteria, pagination cursors, correlation IDs, and opaque decision references are intentionally absent from the page URL. Both read-only operations use same-origin JSON POST bodies. This prevents operational references from entering browser history, copied links, proxy request lines, analytics URLs, or `Referer` values.

`ARCH007` exceptions document why these two POST requests are read-only. They apply only to the dedicated browser adapters and have a dated review gate.

## Server-only authentication

Live mode requires:

```text
ACCOUNTSHIELD_DATA_SOURCE=live
ACCOUNTSHIELD_API_URL=https://internal-accountshield.example
ACCOUNTSHIELD_OPERATOR_TOKEN=<short-lived SECURITY_OPERATOR token>
```

All variables are server-only. None may use the `NEXT_PUBLIC_` prefix. The BFF forwards the bearer credential only to fixed generated operations and sends a validated correlation header. Fixture mode requires no credential and never calls the backend.

## Detail projection

The detail BFF reconstructs a new object from an explicit allowlist. It accepts only:

- decision metadata and masked subject/correlation references;
- ordered reason codes, contributions, and ordinals;
- signal provider, observation time, confidence, state, schema, simulation, and integrity availability;
- policy key, exact version, routing reason, and bounded rollout provenance;
- algorithm, schema, catalog, engine, application, and hash-availability provenance;
- minimized challenge, recovery, outbox, and timeline summaries;
- explicit section availability and the top-level `partial` flag.

A recursive guard rejects response keys that indicate token, secret, payload, unrestricted account reference, IP address, normalized context, fingerprint, or authorization material. Response size, content type, timestamp validity, reason ordering, timeline ordering, and enumerated states are validated before serialization to the browser.

## Honest unavailable states

The UI does not convert missing evidence into zero, success, or confirmed absence:

- `AVAILABLE` means a minimized projection was recorded;
- `NOT_APPLICABLE` means the downstream flow was not required;
- `UNAVAILABLE` means the system cannot assert the downstream state;
- `RECORDED`, `SIMULATED`, `STALE`, and `UNAVAILABLE` distinguish signal provenance;
- low confidence, degraded decisions, partial responses, and missing integrity evidence have text and icon semantics in addition to color.

All timestamps render with an explicit UTC timezone and timeline entries preserve backend ordering.

## Fixture scenarios

Deterministic fixtures cover:

- complete recovery journey;
- degraded decision with stale, low-confidence evidence;
- partial decision with unavailable provenance;
- normal low-risk allow;
- simulated recovery input;
- consumed challenge with low confidence.

Fixture decision references are valid deterministic UUIDs. Raw UUIDs are used only as in-memory lookup keys and JSON request bodies; rendered references are masked.

## Failure behavior

- invalid browser/BFF requests: stable 400;
- missing operator credential/backend 401: stable unauthorized state;
- wrong role/backend 403: stable forbidden state;
- missing authorized investigation: generic stable 404;
- timeout: stable 504;
- malformed or privacy-unsafe success response: stable 502;
- unavailable backend: stable 503;
- every BFF response uses `private, no-store`.

Raw backend titles, details, stacks, hosts, credentials, and internal identifiers are discarded.

## Validation

Run from `frontend/`:

```bash
npm ci --no-audit --no-fund
npm run openapi:check
npm run architecture:test
npm run architecture:check
npm run lint
npm run typecheck
npm run test:unit
npm run build
npm run test:e2e
```

Tests cover generated-operation provenance, server-only authorization, request-body privacy, prohibited-field rejection, deterministic ordering, fixture/live validation, stable problems, URL stability, keyboard interaction, responsive timeline semantics, and accessibility checks.
