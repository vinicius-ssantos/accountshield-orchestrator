# Security policy

## Project status

AccountShield is an educational and portfolio project. It is not production-ready and must not be used as the sole protection mechanism for real accounts, authentication systems, financial transactions, or regulated workloads.

## Simulated challenge providers

TOTP, e-mail, and WebAuthn challenge providers are simulated (see ADR 0004) and controlled by `accountshield.challenge.simulation-enabled` (default `true`). The application refuses to start if the Spring `production` profile is active while that flag remains `true` — this is a deliberate fail-fast boundary, not a runtime toggle to route around. Deploying with real proof requires implementing real provider adapters and setting the flag to `false`; the active mode is observable at `GET /actuator/info`.

## Reporting a vulnerability

Do not open a public issue containing an exploitable vulnerability, secret, personal information, or instructions that could expose another system.

Prefer GitHub private vulnerability reporting through the repository Security tab when that option is available. Include:

- affected commit or version;
- affected component and endpoint;
- concise reproduction steps;
- expected and observed behavior;
- realistic impact;
- suggested mitigation, when known.

Reports that depend on attacking systems without authorization are not accepted.

## Sensitive data

The repository must never contain:

- passwords or password hashes copied from real systems;
- API tokens, private keys, signing secrets, or session cookies;
- production MFA seeds or recovery codes;
- personal information from real users;
- real payment or regulated data.

Use synthetic fixtures and clearly fake credentials in documentation and tests.

## Supported versions

Until the first tagged release, only the latest commit on `main` is considered for security fixes. Older commits and feature branches are not supported.

## Security expectations for contributions

- validate all external input and bound collection and payload sizes;
- avoid logging raw sensitive identifiers or authentication material;
- use constant public responses where account enumeration is possible;
- make externally visible operations idempotent;
- preserve immutable policy and decision history;
- add tests for authorization, retries, duplicate requests, and invalid state transitions;
- document meaningful security trade-offs in an ADR.
