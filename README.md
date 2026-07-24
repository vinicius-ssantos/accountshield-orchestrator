# AccountShield Orchestrator

[![CI](https://github.com/vinicius-ssantos/accountshield-orchestrator/actions/workflows/ci.yml/badge.svg)](https://github.com/vinicius-ssantos/accountshield-orchestrator/actions/workflows/ci.yml)

> Adaptive account-protection decision and orchestration platform with explainable risk policies, step-up challenges, secure recovery, abuse detection, replay, and security simulation.

AccountShield is a portfolio-grade backend platform that evaluates security-sensitive account events and decides whether they should be allowed, monitored, challenged, temporarily blocked, or routed into a secure recovery flow.

The project focuses on the difficult engineering behind account protection: explainable decisions, policy versioning, idempotency, concurrency, state machines, auditability, replay, safe policy rollout, observability, and failure handling. It is **not** intended for real-world authentication or production fraud decisions.

## Why this project exists

Applications with authentication need to answer questions such as:

- Is this login normal for the account?
- Should a password change require stronger verification?
- Is a recovery attempt legitimate or abusive?
- Can a new risk policy be released without locking out valid users?
- Can a historical decision be explained and reproduced later?

AccountShield receives account, session, device, network, and behavioral context; evaluates versioned risk policies; persists an immutable decision trace; and orchestrates the next protection action.

## Product boundary

AccountShield is a **decision and orchestration layer**. It is not a replacement for Keycloak, Auth0, Amazon Cognito, or an identity provider.

### In scope

- protection requests for login, recovery, credential change, and sensitive actions;
- normalized risk signals and weighted contributions;
- versioned and explainable policies;
- decisions such as `ALLOW`, `MONITOR`, `REQUIRE_STEP_UP`, `TEMPORARILY_BLOCK`, and `START_RECOVERY`;
- challenge lifecycle and retry protection;
- secure recovery state machine;
- idempotency, replay protection, rate limits, and cooldowns;
- immutable audit trail;
- deterministic replay and shadow-policy comparison;
- security scenario simulation;
- operational metrics, traces, and structured logs.

### Explicitly out of scope

- storing user passwords;
- issuing production identity tokens;
- real biometric verification;
- real SMS, e-mail, or payment-provider integrations in the first releases;
- machine-learning-based fraud scoring in the MVP;
- production use for security or financial decisions.

## Core flow

```mermaid
flowchart LR
    Client[Client or Identity Provider] --> Intake[Protection Request]
    Intake --> Signals[Signal Collection and Normalization]
    Signals --> Risk[Risk Assessment]
    Risk --> Policy[Versioned Policy Evaluation]
    Policy --> Decision[Explainable Protection Decision]
    Decision --> Audit[(Immutable Decision Trace)]
    Decision --> Challenge[Challenge Orchestrator]
    Decision --> Recovery[Recovery Orchestrator]
    Decision --> Events[Transactional Outbox]
```

A decision response is expected to expose both the outcome and its reasoning:

```json
{
  "decisionId": "dec_01J...",
  "decision": "REQUIRE_STEP_UP",
  "riskScore": 78,
  "riskLevel": "HIGH",
  "reasons": [
    { "code": "NEW_DEVICE", "contribution": 20 },
    { "code": "IMPOSSIBLE_TRAVEL", "contribution": 35 },
    { "code": "RECENT_PASSWORD_CHANGE", "contribution": 23 }
  ],
  "requiredChallenge": "WEBAUTHN_SIMULATED",
  "policyVersion": "2026.07.1"
}
```

## Architecture

The system starts as a modular monolith. Module boundaries are treated as architectural contracts and can evolve into independently deployable services only when operational evidence justifies the split.

Modules:

| Module | Responsibility |
| --- | --- |
| `protection` | Request intake, use-case orchestration, idempotency, and decision API |
| `risk` | Deterministic risk assessment from normalized signals |
| `policy` | Versioned policy evaluation, lifecycle state machine, and shadow mode |
| `challenge` | Step-up challenge lifecycle, attempts, expiry, and retry budget |
| `recovery` | Secure account-recovery state machine with risk-based classification |
| `audit` | Immutable decision trace, replay query API, and security audit events |
| `outbox` | Transactional outbox with scheduled relay and pluggable publisher port |
| `simulation` | Deterministic historical replay and shadow-policy comparison |

Architecture documentation lives under [`docs/architecture`](docs/architecture), and architectural decisions under [`docs/adr`](docs/adr).

## Engineering principles

1. **Explainability is part of the domain model.** A reason is not a log message added after the decision.
2. **Historical decisions are immutable.** Policy changes do not rewrite prior outcomes.
3. **Policies are versioned.** Every decision records the exact policy version used.
4. **Replay is deterministic.** Equal inputs and equal policy versions produce equal outcomes.
5. **External effects are idempotent.** Retries must not create duplicate challenges or events.
6. **Recovery is a state machine.** It is not a single endpoint that resets a credential.
7. **Secure defaults win.** Sensitive data is minimized and operational endpoints are deliberately exposed.
8. **The modular monolith is intentional.** Distribution is earned through evidence, not assumed at project start.

## Technology direction

- Java 25 LTS;
- Spring Boot 4.1;
- Maven;
- Spring Modulith;
- PostgreSQL and Flyway;
- Testcontainers;
- ArchUnit;
- Micrometer and OpenTelemetry;
- Docker Compose;
- GitHub Actions.

Exact dependency versions are pinned in the build and upgraded through reviewed pull requests.

## Delivery status

### Phase 1 — Foundation (delivered)

- executable application skeleton;
- modular architecture and verification tests;
- PostgreSQL/Flyway baseline;
- CI quality gates;
- threat model, ADRs, and contribution guidance.

### Phase 2 — Persistence foundation (delivered)

- PostgreSQL as source of truth with Flyway migrations;
- decision, policy-version, idempotency, and audit schemas;
- Testcontainers integration tests;
- DB-level immutability triggers for policies and audit records.

### Phase 3 — Explainable decision vertical slice (delivered)

- protection request API;
- deterministic signals and risk assessment;
- versioned policies;
- `ALLOW`, `REQUIRE_STEP_UP`, and `TEMPORARILY_BLOCK` outcomes;
- immutable decision trace with explainable reasons.

### Phase 4 — Idempotency and replay protection (delivered)

- caller-supplied idempotency key and deterministic request fingerprint;
- duplicate and concurrent-request behavior;
- conflict detection for reused keys with different payloads;
- bounded replay windows.

### Phase 5 — Challenge orchestration (delivered)

- challenge plan and attempt lifecycle;
- expiration, retry budget, and cooldown;
- simulated TOTP, e-mail, and WebAuthn adapters.

### Phase 6 — Secure recovery (delivered)

- explicit recovery state machine (9 states);
- risk-based classification (immediate, delayed, manual review);
- identity verification via challenge module;
- enumeration-resistant public responses.

### Phase 7 — Policy lifecycle and safe rollout (delivered)

- policy lifecycle state machine (draft, validated, active, retired, rejected);
- immutable activated versions with DB-level enforcement;
- deterministic historical replay;
- shadow-policy evaluation and impact comparison.

### Phase 8 — Operational maturity (delivered)

- transactional outbox with domain event publication;
- sliding-window rate limiting per account;
- Micrometer metrics for decision outcomes and risk scores;
- structured security event logging with sensitive-data redaction;
- Prometheus metrics endpoint and Grafana dashboard definition;
- SLO targets and error budget;
- concurrency and resilience tests for rate limiting and idempotency.

## Security Operations Console

The repository now includes a fixture-driven, read-only Next.js console under [`frontend/`](frontend/). It establishes the operator experience for decisions, recoveries, policies, replay, and operational investigation without enabling privileged mutations.

```bash
cd frontend
npm install
npm run dev
```

Frontend architecture, security constraints, and planned delivery slices are documented in [`frontend/README.md`](frontend/README.md) and [`docs/frontend/architecture.md`](docs/frontend/architecture.md).

## Local development

### Quick start with Docker Compose

```bash
docker compose up -d
```

This starts:

| Service | Port | Purpose |
| --- | --- | --- |
| PostgreSQL 17 | `5432` | Primary data store |
| AccountShield app | `8080` | REST API + actuator + Swagger UI |
| Prometheus | `9090` | Metrics scraping |
| Grafana | `3000` | Dashboards (admin/admin) |

The Grafana dashboard is auto-provisioned from `grafana/accountshield-dashboard.json`.

Interactive API docs are available at `http://localhost:8080/swagger-ui.html` once the application is running.

### Developer workflow

```bash
docker compose up -d postgres

./mvnw verify

./mvnw spring-boot:run
```

No production credentials are required. All external challenge providers are simulated locally.

## Security notice

This repository is an educational and portfolio project. It must not be used as the sole protection mechanism for real accounts, authentication systems, financial transactions, or regulated workloads.

Security reports should avoid disclosing secrets or personal information in public issues. See [`SECURITY.md`](SECURITY.md) once the foundation milestone is merged.

## License

Licensed under the [MIT License](LICENSE).
