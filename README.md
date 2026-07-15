# AccountShield Orchestrator

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

Planned modules:

| Module | Responsibility |
| --- | --- |
| `protection` | Request intake, use-case orchestration, and decision API |
| `signals` | Signal normalization, validation, and contribution model |
| `risk` | Deterministic risk assessment |
| `policy` | Versioned policy evaluation and shadow mode |
| `challenge` | Step-up challenge lifecycle, attempts, expiry, and cooldown |
| `recovery` | Secure account-recovery state machine |
| `abuse` | Idempotency, replay defense, throttling, and abuse controls |
| `audit` | Immutable decision trace and security audit events |
| `simulation` | Attack scenarios, historical replay, and policy comparison |
| `outbox` | Reliable publication of domain events |

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
- Redis for ephemeral controls such as throttling and cooldowns;
- Testcontainers;
- ArchUnit;
- Micrometer and OpenTelemetry;
- Docker Compose;
- GitHub Actions.

Exact dependency versions are pinned in the build and upgraded through reviewed pull requests.

## Delivery roadmap

### Phase 1 — Foundation

- executable application skeleton;
- modular architecture and verification tests;
- PostgreSQL/Flyway baseline;
- CI quality gates;
- threat model, ADRs, and contribution guidance.

### Phase 2 — Explainable decision vertical slice

- protection request API;
- deterministic signals and risk assessment;
- versioned policies;
- `ALLOW`, `REQUIRE_STEP_UP`, and `TEMPORARILY_BLOCK` outcomes;
- immutable decision trace.

### Phase 3 — Challenge orchestration

- challenge plan and attempt lifecycle;
- expiration, retry budget, cooldown, and idempotency;
- simulated TOTP, e-mail, and WebAuthn adapters.

### Phase 4 — Secure recovery

- explicit recovery states and transitions;
- recovery-specific risk checks;
- delayed and high-risk operations;
- abuse detection and manual-review simulation.

### Phase 5 — Replay and safe rollout

- deterministic historical replay;
- shadow policies;
- policy impact comparison;
- false-positive and false-negative simulation.

### Phase 6 — Operational maturity

- transactional outbox;
- tracing and structured security events;
- SLOs and dashboards;
- concurrency, resilience, and chaos scenarios.

## Local development

The executable bootstrap and local infrastructure are introduced by the foundation milestone. The target developer workflow is:

```bash
./mvnw verify

docker compose up -d

./mvnw spring-boot:run
```

No production credentials are required. All external challenge providers are simulated locally.

## Security notice

This repository is an educational and portfolio project. It must not be used as the sole protection mechanism for real accounts, authentication systems, financial transactions, or regulated workloads.

Security reports should avoid disclosing secrets or personal information in public issues. See [`SECURITY.md`](SECURITY.md) once the foundation milestone is merged.

## License

Licensed under the [MIT License](LICENSE).
