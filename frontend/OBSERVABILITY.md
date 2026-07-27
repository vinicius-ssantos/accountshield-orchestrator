# Frontend and BFF observability

This document defines the telemetry boundary for the AccountShield console. The design favors actionable, bounded diagnostics over raw request inspection.

## BFF completion events

Every instrumented BFF use case emits at most one `accountshield.bff.request.completed` event.

Allowed fields:

- `useCase`: stable code-owned use-case name;
- `outcome`: `success`, `invalid_request`, `denied`, `timeout`, `cancelled`, `upstream_unavailable`, `malformed_response`, or `internal_error`;
- `origin`: `route`, `transport`, `adapter`, `spring_api`, or `unknown`;
- `statusClass`: `2xx`, `4xx`, `5xx`, or `none`;
- `retryable`: boolean;
- `durationMs`: bounded request duration;
- `diagnosticCode`: stable code, never an exception message;
- `correlationId`: validated value used to join safe BFF and Spring diagnostics.

The current `runtime_status` route is the reference implementation. Future workflows #69–#74 must reuse the same conventions instead of inventing route-specific log formats.

## Deny-by-default data policy

Never record:

- authorization headers, cookies, tokens, secrets, CSRF values, or challenge material;
- request or response bodies;
- raw query strings or unrestricted URLs;
- stack traces for expected authorization, validation, timeout, or upstream failures;
- raw account, user, device, network, policy, recovery, decision, or timeline identifiers;
- exception messages supplied by upstream systems or users.

Metric dimensions must come from code-owned allowlists. User-controlled strings must never become metric names or tags.

## Web Vitals

The browser reports only the standard `CLS`, `FCP`, `INP`, `LCP`, and `TTFB` metrics to the narrow same-origin `/api/bff/telemetry/web-vitals` endpoint.

Accepted dimensions are limited to:

- rating: `good`, `needs-improvement`, or `poor`;
- navigation type: `navigate`, `reload`, `back-forward`, `prerender`, `restore`, or `unknown`;
- route category: `home`, `decisions`, `policies`, `challenges`, `recovery`, `audit`, `design-system`, or `unknown`.

Raw paths, metric IDs, page URLs, searches, correlation IDs, and workflow identifiers are rejected. Unknown payload fields fail validation.

## Sampling and environments

`ACCOUNTSHIELD_WEB_VITALS_SAMPLE_RATE` is a server-only number from `0` to `1`.

- local, test, and preview default to `1`;
- production defaults to `0.1`;
- CI validates the contract and does not require an external telemetry backend;
- production-like environments should explicitly configure the rate.

BFF completion events are emitted for every request because they also support security troubleshooting. Downstream storage may sample successful events, but must retain failures and must not add high-cardinality dimensions.

## Retention

Recommended operational policy:

- detailed structured events: 14 days;
- aggregated latency, rate, error, timeout, and Web Vitals series: 30 days;
- no raw payload archive;
- access restricted to operators with a troubleshooting need.

The deployment platform owns the final retention implementation and must document any deviation.

## Troubleshooting

1. Obtain the safe `x-correlation-id` and stable problem `code` from the failed response.
2. Search BFF completion events by correlation ID.
3. Use `origin`, `outcome`, `statusClass`, and `diagnosticCode` to identify the failing boundary.
4. When backend tracing from #24 is available, continue the same correlation through Spring spans and transaction-aware diagnostics.
5. Do not request or paste credentials, challenge values, raw identifiers, or request bodies into tickets.

## Dependencies and limitations

- #36 owns the final public Problem Details catalog and internal diagnostic terminology.
- #24 owns end-to-end backend tracing, transaction-aware metrics, OTLP export, and dashboards.
- This frontend implementation deliberately exposes a sink interface so an infrastructure metrics exporter can be added without changing route behavior or privacy rules.
