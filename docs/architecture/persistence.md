# Persistence ownership

## Source of truth

PostgreSQL is authoritative for correctness-sensitive state. Ephemeral controls such as rate-limit counters are held in-process; ADR 0008 documents this choice and the conditions under which a distributed store may be introduced.

Flyway owns schema creation and evolution. Hibernate runs with `ddl-auto=validate`, so the application fails startup when entity mappings and the migrated schema diverge.

## Schema ownership

| Schema | Owning module | Current tables | Responsibility |
| --- | --- | --- | --- |
| `protection` | `protection` | `protection_request`, `idempotency_record` | Accepted request identity, fingerprint, status, and persisted logical result reference |
| `policy` | `policy` | `policy_version` | Immutable activated/retired versions and mutable pre-activation lifecycle |
| `audit` | `audit` | `decision_trace`, `decision_reason` | Append-only evidence and reason contributions |
| `challenge` | `challenge` | `challenge_plan` | Purpose/context-bound verification state, attempts, expiry, and consumption |
| `recovery` | `recovery` | `recovery_authorization`, `recovery_flow` | Expirable authorization and recovery state machine |
| `outbox` | `outbox` | `outbox_event` | Transactional publication intent and relay state |

Schema ownership does not permit direct repository or entity access from another module. Cross-module contracts use identifiers, immutable values, public module APIs, or domain events.

## JPA boundary

Persistence types live under each module's `internal.persistence` package. They remain implementation details even when Java visibility is required for framework proxying.

The project does not use:

- bidirectional relationships between modules;
- cross-module JPA entity associations;
- repositories shared through a generic infrastructure package;
- Hibernate schema generation.

A cross-module identifier may be retained as evidence without becoming an operational foreign key. In particular, `recovery_flow.originating_decision_id` correlates audit evidence, while `recovery_flow.authorization_id` is the operational referential boundary.

## Immutability

### Audit

Decision traces and reason contributions are append-only. Database triggers reject updates and deletes. Current immutability detects ordinary mutation but is not yet a cryptographic tamper-evident chain; issue #40 tracks hash chaining.

### Policy

A policy version may evolve while it is a draft or validated candidate. Once active or retired, protected fields are immutable. Corrections require a new version.

### Recovery authorization

`recovery_authorization` is immutable except for the first transition of `consumed_at` from null. PostgreSQL enforces:

- unique authorization ID;
- unique protection request ID;
- unique decision ID;
- bounded risk score;
- supported directive values;
- expiry after issuance;
- consumption between issuance and expiry;
- trigger-protected immutable fields;
- no mutation after consumption.

## Idempotency

The database owns uniqueness for an idempotency key. Application semantics distinguish:

- same key and same request fingerprint: return the original logical result;
- same key and different fingerprint: stable conflict;
- expired key: governed by an explicit retention and reuse policy.

Sequential retry behavior is implemented. Concurrent winner re-read and cleanup hardening remain in issue #22.

## Challenge persistence

`challenge_plan` stores purpose, context, type, status, retry budget, timestamps, and simulated verification material required by the current demo.

The persistence model must preserve:

- one purpose and context binding for the challenge lifetime;
- bounded attempts;
- expiry and terminal-state enforcement;
- single-use successful consumption.

Secret hashing, provider-specific representation, optimistic locking, and production-profile simulation guards remain in issues #20, #37, and #38.

## Recovery persistence

### Authorization

A `START_RECOVERY` decision synchronously emits `RecoveryAuthorizationIssued`. The recovery module persists the authorization within the originating protection transaction. A decision cannot commit successfully while its required authorization fails to persist.

The authorization stores:

- authorization ID;
- protection request ID;
- decision ID;
- opaque account reference;
- recovery directive;
- risk score;
- issue, expiry, and consumption timestamps.

### Flow

`recovery_flow` stores:

- recovery ID;
- account reference and event type derived from authorization;
- risk score and classification;
- current state;
- identity challenge ID;
- initiation, update, and eligibility timestamps;
- reviewer where applicable;
- authorization ID;
- protection request and originating decision correlation IDs.

A unique foreign key from flow to authorization guarantees at most one flow per authorization. The authorization row is locked pessimistically during consumption. Dedicated optimistic locking and broader stale-write mapping remain in issues #18 and #37.

## Referential-integrity policy

Referential integrity follows the operational authority:

- recovery flow references recovery authorization;
- recovery challenge correlation is enforced through public challenge contracts and persisted IDs;
- audit decision IDs in recovery remain evidence correlation, not execution authority;
- missing audit projection must not invalidate an existing authorization.

When a database foreign key would create an incorrect module-availability dependency, the equivalent invariant must be documented and integration-tested.

## Migration safety

A migration that makes a column non-null, unique, or referential must follow this order when existing rows may be present:

1. add the nullable column or new table;
2. backfill deterministically;
3. validate duplicates and invalid historical state;
4. create supporting indexes;
5. add constraints and foreign keys;
6. set non-null where required;
7. add triggers only after data is valid;
8. verify through Testcontainers and Hibernate schema validation.

Migration V10 follows this pattern for explicit recovery authorization and safely backfills historical recovery flows.

## Outbox boundary

The outbox table persists a domain change and its publication intent in one PostgreSQL transaction. `OutboxRelay` polls unpublished events and dispatches them through `OutboxEventPublisher` (ADR 0009).

The current relay remains a baseline. Explicit claim states, `FOR UPDATE SKIP LOCKED`, bounded backoff, dead letters, retention, and versioned minimized integration payloads are tracked by issue #23.

## Pending persistence hardening

- recovery and challenge `@Version` fields and controlled stale conflicts: #18 and #37;
- broader score, attempts, timestamp, and active-record constraints: #37;
- runtime/migration/read-only database roles: #25;
- bounded retention jobs and metrics: #25 and #32;
- audit hash chain and integrity verifier: #40;
- versioned recovery classification provenance: #31.
