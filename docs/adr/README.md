# Architecture decision records

Architecture decision records preserve the reasoning and constraints behind AccountShield's design. The architecture pages describe the current executable system; ADRs explain why important choices were made.

## Index

| ADR | Status | Decision | Related architecture/features |
| --- | --- | --- | --- |
| [0001](0001-start-as-a-modular-monolith.md) | Accepted | Start as a modular monolith and extract only with evidence | [Architecture baseline](../architecture/README.md) |
| [0002](0002-use-postgresql-as-source-of-truth.md) | Accepted | Use PostgreSQL as the correctness source of truth | [Persistence](../architecture/persistence.md) |
| [0003](0003-idempotency-via-caller-key-and-fingerprint.md) | Accepted | Identify logical retries through caller key and deterministic fingerprint | [Feature catalog](../features/README.md), issues #18 and #22 |
| [0004](0004-challenge-orchestration-via-simulated-providers.md) | Accepted | Model purpose-bound challenge orchestration with simulated providers | Challenge section in the [feature catalog](../features/README.md) |
| [0005](0005-recovery-flow-state-machine.md) | Accepted | Enforce an explicit risk-gated recovery state machine | [Recovery architecture](../architecture/recovery.md) |
| [0006](0006-deterministic-replay-and-shadow-evaluation.md) | Accepted | Keep replay and shadow evaluation deterministic and side-effect-free | Replay section in the [feature catalog](../features/README.md) |
| [0007](0007-policy-lifecycle-state-machine.md) | Accepted | Use immutable activated policy versions and controlled lifecycle transitions | Policy section in the [feature catalog](../features/README.md) |
| [0008](0008-in-memory-rate-limiting.md) | Accepted | Use in-memory rate limiting for the current single-instance baseline | [Architecture baseline](../architecture/README.md) |
| [0009](0009-outbox-relay-with-simulated-publisher.md) | Accepted | Persist publication intent transactionally and use a simulated relay | Outbox section in the [feature catalog](../features/README.md) |
| [0010](0010-recovery-trust-boundaries.md) | Accepted | Use explicit `START_RECOVERY` and an immutable consumable recovery authorization; audit remains evidence | [Recovery](../architecture/recovery.md), [invariants](../architecture/invariants.md) |
| [0011](0011-jwt-resource-server-with-local-issuer.md) | Accepted | Authorize sensitive APIs with a JWT resource server backed by a local, per-boot key pair | Issue #19 |
| [0012](0012-adopt-read-only-first-operator-console.md) | Accepted | Ship investigation and explanation before administrative mutation; gate every mutation behind its own backend readiness contract | [Frontend architecture](../frontend/architecture.md) |
| [0013](0013-use-backend-for-frontend-security-boundary.md) | Accepted | Browser code never manages long-lived backend credentials; a server-only Next.js BFF is the security boundary | [Frontend architecture](../frontend/architecture.md) |
| [0014](0014-generate-frontend-api-clients-from-openapi.md) | Accepted | Generate frontend API clients from the published OpenAPI contract rather than hand-writing them | [Frontend architecture](../frontend/architecture.md) |
| [0015](0015-use-deterministic-frontend-data-sources.md) | Accepted | Use an explicit, deterministic data-source abstraction (fixtures vs. live) with no silent fallback | [Frontend architecture](../frontend/architecture.md) |
| [0016](0016-prefer-react-server-components.md) | Accepted | Prefer React Server Components by default and minimize client boundaries | [Frontend architecture](../frontend/architecture.md) |
| [0017](0017-bff-operator-session-cookie-and-csrf-design.md) | Accepted | BFF-managed opaque, HMAC-signed session cookie backed by an in-memory server-side store, with double-submit CSRF | Issue #78 |
| [0018](0018-operator-recovery-review-mutation.md) | Accepted | First console mutation: operator recovery review through the BFF, gated by the #78 session guard with no env-token fallback, removing `recoveries` from the read-only architecture scope | Issue #194 |
| [0019](0019-operator-policy-rollout-mutation.md) | Accepted | Third console mutation: operator policy rollout controls (start/adjust/rollback) through the BFF, reusing policy lifecycle's step-up verify route and a no-step-up, distinctly-confirmed immediate rollback | Issue #200 |
| [0020](0020-operator-outbox-requeue-mutation.md) | Accepted | Fourth console mutation: operator outbox dead-letter requeue through the BFF, the first with no step-up gate (operational remediation, not a privileged security action) | Issue #203 |
| [0021](0021-operator-evidence-export-mutation.md) | Accepted | Fifth console mutation: operator evidence export/verify through the BFF, the first feature to split one export route (mutation) from one verify route (read) within the same feature | Issue #214 |
| [0022](0022-colocate-nextjs-operations-console.md) | Accepted | Colocate the Next.js operations console in this repository rather than a separate repository | Issue #58 |

## ADR lifecycle

Use the following statuses:

- **Proposed:** decision is under review and must not be treated as an executable guarantee;
- **Accepted:** decision constrains implementation and future changes;
- **Superseded:** another ADR replaces the decision; both records remain available;
- **Deprecated:** retained for history but no longer recommended;
- **Rejected:** considered and explicitly not adopted.

## When a new ADR is required

Create or supersede an ADR when a change:

- changes module boundaries or distribution strategy;
- changes the source of truth or transactional authority;
- introduces or replaces an authorization mechanism;
- changes idempotency, retry, concurrency, or delivery guarantees;
- establishes a long-lived public API/event versioning policy;
- adopts a new cryptographic or data-retention strategy;
- accepts a significant operational trade-off;
- reverses an existing accepted decision.

A small refactor, library upgrade, or implementation detail normally does not require an ADR unless it changes one of these constraints.

## Required ADR structure

Each ADR should contain:

1. title, status, date, and update/supersession metadata;
2. context and problem;
3. decision;
4. alternatives considered;
5. positive and negative consequences;
6. executable guardrails;
7. migration/compatibility implications;
8. revisit criteria;
9. links to issues, architecture pages, migrations, and tests.

## Consistency rule

After an ADR is accepted and implemented:

- add it to this index;
- update the feature catalog status;
- update the relevant architecture and invariant documents;
- add or update automated tests proving the guardrails;
- ensure README claims do not exceed the delivered implementation.
