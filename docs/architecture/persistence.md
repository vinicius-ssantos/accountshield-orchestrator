# Persistence ownership

## Source of truth

PostgreSQL is authoritative for correctness-sensitive state. Redis is intentionally absent from the initial persistence foundation and may later be used only for reconstructible ephemeral controls.

## Schema ownership

| Schema | Owning module | Initial tables |
| --- | --- | --- |
| `protection` | `protection` | `protection_request`, `idempotency_record` |
| `policy` | `policy` | `policy_version` |
| `audit` | `audit` | `decision_trace`, `decision_reason` |
| `outbox` | `outbox` | `outbox_event` |

Schema ownership does not permit direct repository or entity access from another module. Cross-module contracts use identifiers, immutable values, public module APIs, or domain events.

## JPA boundary

Persistence types live under each module's `internal.persistence` package. They are implementation details even when Java visibility must be public for framework proxying.

The project does not use:

- bidirectional relationships between modules;
- cross-module entity associations;
- repositories shared through a generic infrastructure package;
- Hibernate schema generation.

Flyway creates the schema and Hibernate runs with `ddl-auto=validate`.

## Immutability

Decision traces and reason contributions are append-only. Database triggers reject updates and deletes.

A policy version may evolve while it is a draft or validated candidate. Once active, it becomes immutable. Corrections require a new version.

## Idempotency

The database owns the uniqueness guarantee for an idempotency key. Application logic will later distinguish:

- the same key with the same request fingerprint, which returns the original logical result;
- the same key with a different fingerprint, which is a conflict;
- an expired key, whose retention and reuse policy must be explicit.

## Outbox boundary

The outbox table is introduced before a publisher. It allows future use cases to persist a domain change and its publication intent in one PostgreSQL transaction. Kafka or another broker is not part of this foundation.
