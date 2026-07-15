# ADR 0002: Use PostgreSQL as the source of truth

- Status: Accepted
- Date: 2026-07-15

## Context

AccountShield must preserve protection requests, idempotency claims, policy versions, immutable decision traces, and events awaiting publication across retries and process restarts.

These records participate in correctness-sensitive workflows. Losing or duplicating one can create a second logical decision, evaluate an unintended policy version, rewrite historical evidence, or publish a domain event more than once.

Redis is useful for reconstructible, short-lived controls, but its role must not make durable security decisions depend on cache retention.

## Decision

PostgreSQL is the authoritative store for:

- protection requests;
- durable idempotency records;
- policy definitions and versions;
- decision traces and reason contributions;
- recovery and challenge state when those modules are introduced;
- transactional outbox records.

Flyway owns schema evolution. Hibernate validates mappings but does not create or modify the schema.

Each application module owns its tables, entities, repositories, and migrations conceptually. Cross-module references use stable identifiers and do not use JPA object relationships. A module must not import another module's persistence types.

Redis may later hold reconstructible ephemeral data such as rate-limit counters, short-lived cooldowns, and caches. Correctness must survive Redis eviction or complete loss.

## Database protections

- durable idempotency keys have a uniqueness constraint;
- policy key and version pairs have a uniqueness constraint;
- only one active policy version is allowed per policy key;
- activated policy versions reject update and delete operations;
- decision traces and reason contributions reject update and delete operations;
- risk scores and lifecycle states use database checks;
- timestamps use `TIMESTAMPTZ` and application persistence uses UTC.

## Consequences

### Positive

- transaction boundaries can protect decisions, audit records, and outbox inserts together;
- correctness survives application restarts and cache loss;
- invariants are enforced below the application layer;
- Testcontainers can exercise the production database engine in CI;
- module persistence ownership remains explicit.

### Negative

- PostgreSQL becomes required for application startup;
- migration quality becomes a release concern;
- append-only and immutable constraints require explicit lifecycle design;
- schema ownership must be reviewed whenever modules collaborate.

## Revisit criteria

Revisit only when measured scale, data governance, ownership, or failure-isolation requirements justify splitting a module's authoritative data store. A cache or message broker must not replace durable state merely to reduce local complexity.
