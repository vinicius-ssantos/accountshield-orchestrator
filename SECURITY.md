# Security policy

## Project status

AccountShield is an educational and portfolio project. It is not production-ready and must not be used as the sole protection mechanism for real accounts, authentication systems, financial transactions, or regulated workloads.

## Simulated challenge providers

TOTP, e-mail, and WebAuthn challenge providers are simulated (see ADR 0004) and controlled by `accountshield.challenge.simulation-enabled` (default `true`). The application refuses to start if the Spring `production` profile is active while that flag remains `true` — this is a deliberate fail-fast boundary, not a runtime toggle to route around. Deploying with real proof requires implementing real provider adapters and setting the flag to `false`; the active mode is observable at `GET /actuator/info`.

## Production secrets and the local demo profile

The `production` Spring profile also activates `ProductionSecretsGuard`, which refuses to boot if any operator-managed secret (challenge HMAC, pseudonym, webhook encryption key, active KEK, subject-id secret) is still at its repository-published default value. The AES-256 key-encryption key and webhook secret-encryption key must be base64-encoded 32-byte material — generate each with `openssl rand -base64 32` (see [`docs/RELEASING.md`](docs/RELEASING.md)).

Conversely, the `local` profile (used by `docker compose up`) enables `POST /dev/tokens`, an **unauthenticated** endpoint that mints privileged JWTs carrying any requested role, so the demo consumer can self-authenticate. This is intentional for local exploration but means the compose stack must never be exposed outside `localhost`.

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

Only the latest tagged release (`v1.0.0` and later, as they ship) and the latest commit on `main` are considered for security fixes. Older tags, commits, and feature branches are not supported.

## Security expectations for contributions

- validate all external input and bound collection and payload sizes;
- avoid logging raw sensitive identifiers or authentication material;
- use constant public responses where account enumeration is possible;
- make externally visible operations idempotent;
- preserve immutable policy and decision history;
- add tests for authorization, retries, duplicate requests, and invalid state transitions;
- document meaningful security trade-offs in an ADR.
