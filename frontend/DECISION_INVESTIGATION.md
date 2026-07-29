# Decision investigation console

This slice completes frontend issue #69 against the authorized backend contract delivered by #168.

## Data flow

```text
browser /decisions
  -> POST /api/bff/decision-search
  -> server-only BFF validation and telemetry
  -> generated searchDecisionInvestigations operation
  -> POST /api/v1/operator/decisions/search
  -> privacy-minimized DecisionSearchResponse
```

The browser never receives `ACCOUNTSHIELD_OPERATOR_TOKEN` and never calls the Spring API origin directly.

## URL privacy

Decision filters are intentionally not represented in the page URL. This includes:

- exact correlation ID;
- opaque pagination cursor;
- event/outcome/risk filters;
- policy version;
- investigation time range.

The browser submits these fields as a same-origin JSON body. This prevents them from entering browser history, copied links, proxy request lines, analytics URLs, or `Referer` values.

## Server-only authentication

Live mode requires:

```text
ACCOUNTSHIELD_DATA_SOURCE=live
ACCOUNTSHIELD_API_URL=https://internal-accountshield.example
ACCOUNTSHIELD_OPERATOR_TOKEN=<short-lived SECURITY_OPERATOR token>
```

All three variables are server-only. None may use the `NEXT_PUBLIC_` prefix. The BFF forwards only the bearer token and validated correlation header to the fixed operator-search endpoint.

Fixture mode does not require a token and never calls the backend.

## Response minimization

The BFF accepts only the dedicated investigation projection:

- decision reference;
- correlation ID;
- event and outcome;
- risk score and band;
- policy key/version;
- decision timestamp;
- degraded, simulated, and provenance-availability flags.

Raw account references, normalized context, request fingerprints, challenge values, recovery authorization material, exception details, and persistence entities are not accepted or rendered.

## Failure behavior

- invalid browser/BFF requests: stable 400;
- missing operator token/backend 401: stable unauthorized state;
- wrong role/backend 403: stable forbidden state;
- timeout: stable 504;
- malformed response: stable 502;
- unavailable backend: stable 503;
- all responses use `private, no-store`.

Raw backend title, detail, stack, host, token, and internal identifiers are discarded.

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

The included tests cover generated operation provenance, server-only authorization, POST-body privacy, fixture/live response validation, cursor traversal, safe Problem Details, URL stability, keyboard submission, and axe accessibility.
