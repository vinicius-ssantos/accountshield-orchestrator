# AGENTS.md

## Mission

Build AccountShield as a portfolio-grade, explainable account-protection decision and orchestration platform. Prefer correctness, auditability, deterministic behavior, and explicit domain modeling over feature volume.

## Read first

Before changing code, read:

1. `README.md`;
2. `docs/architecture/README.md`;
3. all accepted ADRs under `docs/adr`;
4. `SECURITY.md` for data-handling constraints.

## Toolchain

- Java 25;
- Spring Boot 4.1;
- Spring Modulith 2.1;
- Maven;
- JUnit 5.

Primary verification command:

```bash
mvn --batch-mode --no-transfer-progress verify
```

Do not claim a change is complete unless the relevant tests pass or the limitation is reported explicitly.

## Architecture rules

- Keep domain capabilities in top-level application modules below `io.github.viniciusssantos.accountshield`.
- Do not create generic root packages named `service`, `repository`, `entity`, `model`, `common`, or `util`.
- A module owns its persistence model and repositories.
- Never access another module's internal implementation or persistence entity.
- Collaborate through intentional public APIs or domain events.
- Keep web, persistence, cache, and provider details behind module-owned adapters.
- Add or update an ADR for a consequential architectural decision.
- Keep `ApplicationModules.verify()` passing.

## Domain rules

- Decisions and policy versions are immutable after publication.
- Explainability is part of the domain output; do not reconstruct reasons from logs.
- Live evaluation and historical replay must be deterministic for the same versioned inputs.
- Replays and shadow policies must never trigger external side effects.
- Externally visible commands must be idempotent.
- Recovery and challenge flows must use explicit state transitions.
- Use UTC instants for persisted time and inject clocks into time-sensitive domain logic.

## Security rules

- Never add real credentials, personal information, passwords, MFA seeds, private keys, or production data.
- Treat all caller-provided signals and headers as untrusted.
- Bound payloads, collections, retry counts, and time windows.
- Do not log raw secrets or unrestricted sensitive identifiers.
- Avoid account-enumeration differences in public errors.
- Add negative tests for invalid transitions, duplicate requests, authorization failures, and boundary values.

## Java conventions

- Prefer immutable records and value objects for domain data where appropriate.
- Validate invariants at construction boundaries.
- Keep controllers thin and free of domain decisions.
- Avoid static mutable state and hidden system-clock access.
- Use descriptive domain names rather than framework-oriented names.
- Do not introduce an abstraction until it protects a real boundary or variation.

## Testing expectations

Each feature should include the smallest useful combination of:

- unit tests for domain rules;
- boundary and negative tests;
- module integration tests;
- architecture verification;
- Testcontainers tests when persistence or infrastructure behavior matters;
- concurrency tests for idempotency or state transitions.

A test should describe observable behavior, not implementation details.

## Scope discipline

Prefer vertical slices that leave the repository working. Do not introduce Kafka, microservices, machine learning, real SMS/e-mail providers, or production identity integrations without an accepted issue and ADR that justify them.

## Pull requests

Keep PRs reviewable and explain:

- the problem and domain behavior;
- architectural impact;
- security impact;
- tests performed;
- deferred work and explicit non-goals.
