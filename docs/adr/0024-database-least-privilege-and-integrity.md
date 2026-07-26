# ADR 0024: Database least privilege, referential integrity, and retention completeness

- Status: Accepted
- Date: 2026-07-26

## Context

P2 issue #25. Local configuration uses a single Postgres user for both migrations and runtime — the runtime code path can therefore alter schemas, triggers, and immutability controls it should never need to touch. Several cross-module identifiers lacked database-level referential integrity, and one temporal table (`recovery.recovery_authorization`) had no retention job at all.

Direct code inspection found:

- Zero `GRANT`/`REVOKE`/`CREATE ROLE` statements across all 19 existing migrations — role separation is entirely greenfield.
- Three existing immutability triggers/functions to protect: `audit.reject_audit_mutation()` (decision_trace/decision_reason), `policy.protect_activated_policy_version()`, `recovery.protect_recovery_authorization()`.
- Real FK gaps: `audit.decision_trace.protection_request_id`, `recovery.recovery_authorization.protection_request_id`, `recovery.recovery_flow.identity_challenge_id`, `policy.policy_rollout(policy_key, candidate_version)` — none had an enforced foreign key, despite each being a genuine "this row is meaningless without its parent" relationship. `outbox.outbox_event.aggregate_id` and `challenge.challenge_plan.context_id` are deliberately polymorphic (their target type varies) and are correctly left unconstrained, backed by a `CHECK` on their discriminator column instead.
- `recovery.recovery_authorization.decision_id` looked like the same kind of gap at first, but is **not** one: ADR 0010 explicitly establishes that "a previously issued authorization remains usable when the audit projection is unavailable or absent," and `RecoveryIntegrationTest.existingAuthorizationWorksWithoutAuditProjection` exercises exactly that (an authorization whose `decision_id` has no corresponding `decision_trace` row, by design). An initial version of this change added the FK anyway and only caught the contradiction when that test failed in CI — see Consequences.
- `recovery.recovery_flow.originating_decision_id` was deliberately **stripped** of its FK in migration V10 (superseded by the `authorization_id -> recovery_authorization.decision_id` chain) — confirmed this was an intentional historical design choice, not an oversight, and left untouched.
- The four existing retention jobs (idempotency, challenge, recovery-flow, outbox — the last added in #23) share one exact shape and have no cross-instance coordination, but their batched `DELETE ... LIMIT` pattern is already safe under naive concurrent execution: a second instance deleting an already-deleted row is a harmless zero-row no-op, unlike #23's outbox-claim problem (which needed `SKIP LOCKED` because a double-claim there causes a real side effect — double-publish).
- `recovery.recovery_authorization` had no retention job — a genuine, standalone gap (ADR 0010 already frames it as distinct from audit evidence, so purging it does not conflict with the append-only-evidence principle).
- `protection.protection_request` was documented "no automated purge yet," but once the missing `decision_trace` FK is added, it becomes transitively pinned forever anyway (decision traces are never deleted) — the correct fix is documenting indefinite retention, not building a purge job that would eventually violate the new FK.
- No permission-test precedent; every integration test connects to Postgres as the Testcontainers-provisioned owner-equivalent user.

## Decision

### Migration `V20`: two new roles, explicit grants, default privileges for future tables

`accountshield_runtime` (the application's day-to-day DML) and `accountshield_readonly` (future reporting connections) are created and granted directly in SQL:

- `accountshield_runtime` gets `SELECT`/`INSERT`/`UPDATE`/`DELETE` on every regular table, but only `SELECT`/`INSERT` on `audit.decision_trace`/`audit.decision_reason` — append-only enforced at the grant level, in addition to (not instead of) the existing `reject_audit_mutation()` trigger.
- `ALTER DEFAULT PRIVILEGES` is set per schema so tables added by *future* migrations automatically inherit the correct posture (audit schemas append-only, everything else full DML) without needing a manual grant statement added to every subsequent migration.
- `accountshield_readonly` gets `SELECT` everywhere, nothing else.
- Neither role owns, or has any explicit grant on, the three immutability trigger functions or their triggers. This is what makes "runtime role cannot drop or modify audit triggers" true with **no extra `REVOKE`** — Postgres's default-deny model means a non-owner role with no explicit grant cannot `ALTER`/`DROP` a function or a trigger defined on a table it doesn't own; trigger *firing* itself requires no grant to the role whose DML caused it.

### Referential integrity: three real gaps closed, two deliberate non-gaps left alone

FKs added for `decision_trace.protection_request_id`, `recovery_authorization.protection_request_id`, `recovery_flow.identity_challenge_id`, and the composite `policy_rollout(policy_key, candidate_version) -> policy_version(policy_key, version)`. `outbox_event.aggregate_id` and `challenge_plan.context_id` remain unconstrained (genuinely polymorphic, already governed by a `CHECK` on their discriminator column) — matching, not changing, the existing documented pattern. `recovery_authorization.decision_id` also remains unconstrained, deliberately: see Context.

### Scoping decision: prove the roles are correct without switching the running application's connection

**The application's own datasource is not switched to `accountshield_runtime` in this repository's `compose.yaml` or test suite.** Reasoning: Spring Boot resolves Flyway's datasource from either an explicit `spring.flyway.url` (a static value, structurally incompatible with Testcontainers' dynamically-assigned port — there is no way to point a static YAML property at a port only known after the container starts) or the same connection as `spring.datasource`/`@ServiceConnection` (which would force Flyway itself to run as the restricted role, defeating the purpose, since Flyway needs `CREATE`/`ALTER` rights the runtime role must never have). Solving this generically would require either a `@DynamicPropertySource` extracting the live container port for a second, explicit Flyway connection, or restructuring the 35+ existing integration test files that all share one `PostgreSqlTestConfiguration` — a large, separate, high-risk effort disproportionate to this issue.

Instead: the roles and grants are created and **independently proven correct** via `DatabaseRolePermissionIntegrationTest`, which opens its own raw JDBC connection directly to the same Testcontainers instance, authenticating as `accountshield_runtime` — bypassing Spring's managed datasource entirely — and asserts ordinary DML succeeds, `UPDATE`/`DELETE` on audit tables is denied, and dropping/altering the immutability trigger function or trigger is denied. Genuinely switching the *deployed* application's own connection to the restricted role is a standard two-phase production pattern (run migrations once as a separate step with owner credentials — e.g. `SPRING_FLYWAY_ENABLED=false` for the long-running app process, migrations applied by a one-off job or CLI invocation — then run the app itself with only the restricted role) documented here, not wired into the local-dev compose file.

### Retention: one genuine new job, metrics added to all five

`RecoveryAuthorizationRetentionCleanup` (new, `recovery/internal`) follows the four existing jobs' exact shape, purging rows whose `expires_at` is past a configurable TTL (default 30 days) — mirrors `ChallengePlanRetentionCleanup`'s reasoning: there is no `updated_at` column, so `expires_at` (consumed or not) is the correct retention anchor, since an authorization past its expiry can never be acted on again regardless of whether it was ever consumed.

All five retention jobs (the four existing plus this new one) gain a shared `Counter` (`accountshield.retention.purged`, tagged `job=<name>`, and `status` for the two-category outbox job) — this is the "observable" half of "cleanup jobs are bounded, observable, and safe under concurrency." "Bounded" and "safe under concurrency" already held before this change (existing `MAX_BATCHES_PER_TICK`/batch-size caps; harmless no-op double-deletes, reasoned above) and are documented here as already-satisfied guardrails rather than new infrastructure — no distributed scheduling lock (e.g. ShedLock) is introduced, since none of these jobs has a side effect that makes a duplicate delete attempt unsafe.

`RecoveryAuthorizationRetentionCleanup`'s delete is **not** a plain `expires_at < cutoff` bulk delete: `recovery.recovery_flow.authorization_id` already carries a (pre-existing, unrelated to this issue) foreign key back to `recovery_authorization`, so an authorization that spawned a still-live (non-terminal) flow cannot be deleted without violating that constraint — correctly so, since the flow still needs it. The delete query adds `AND NOT EXISTS (SELECT 1 FROM recovery.recovery_flow WHERE recovery_flow.authorization_id = recovery_authorization.id)`, so an authorization is only purged once no flow references it any more (whether none was ever created, or `RecoveryFlowRetentionCleanup` already purged it). This was caught by `RecoveryAuthorizationRetentionCleanupTest` failing in CI against a shared Testcontainers instance carrying another test class's non-terminal flow fixture.

### A real flush-ordering bug the new `decision_trace` FK exposed

Adding `fk_decision_trace_protection_request` broke nearly every integration test that exercises `ProtectionDecisionApplicationService.decide()`. The cause: `decisionTraceRecorder` (`JdbcDecisionTraceRecorder`) inserts via a raw `JdbcTemplate` statement, entirely outside the JPA persistence context, while `protectionRequestRepository.save(...)` is a JPA operation that Hibernate may leave queued in its session write-behind cache rather than issuing to the physical connection immediately. The raw insert's FK check only sees what has actually reached the connection — so a still-queued `protection_request` row was invisible to it, even though both operations run in the same transaction and would have committed together correctly with no FK in place. The fix is `protectionRequestRepository.saveAndFlush(...)` instead of `.save(...)`: this forces the row to the physical connection (visible to the subsequent raw insert) without committing the transaction (so the whole decision still rolls back atomically on any later failure, per ADR 0010's requirement). This is a general hazard whenever a JPA write and a raw-JDBC write in the same transaction have a new FK dependency between them — worth remembering if a future migration adds another FK crossing this same boundary.

## Alternatives considered

- **A `@DynamicPropertySource`-based split Flyway/runtime datasource for the whole test suite** — rejected for this issue; real, buildable, but a large, separate effort touching dozens of existing test files disproportionate to a P2 issue that can otherwise be fully and independently verified.
- **A distributed lock (ShedLock) for retention jobs** — rejected; the existing batched-delete pattern has no correctness risk under naive concurrent execution, only possible (harmless) duplicate work in a narrow race window.
- **A purge job for `protection.protection_request`** — rejected once the `decision_trace` FK exists: `protection_request` becomes transitively pinned forever regardless, so a purge job would either be dead code or eventually fail against the new FK.

## Consequences

### Positive

- a restricted runtime role and read-only role now exist as real, provable, ready-to-use database artifacts;
- the runtime role cannot alter or drop any of the three immutability triggers/functions, and cannot `UPDATE`/`DELETE` audit rows, by construction (Postgres's own default-deny model), not by application-level convention alone;
- three real orphan-insert paths are now rejected at the database level regardless of which application code path (or bug) attempts them;
- `recovery.recovery_authorization` no longer accumulates indefinitely, without breaking a still-live recovery flow's reference to it;
- all five retention jobs are now individually observable via a consistent metric;
- ADR 0010's "audit is evidence, not authority" guarantee for `recovery_authorization` is preserved explicitly, not accidentally broken by a well-intentioned but overly broad integrity pass.

### Negative

- the application's own deployed connection has not actually been switched to the restricted role within this repository's tooling — "application starts and operates with the restricted runtime role" is proven at the database-grant level and documented as a production configuration, not exercised end-to-end by this repository's own CI;
- the new FKs on `decision_trace`/`recovery_authorization`/`recovery_flow`/`policy_rollout` mean any future test or ops script inserting these rows directly via raw SQL must first create valid parent rows — already fixed at every existing call site found by direct inspection, but a real, ongoing constraint on future raw-SQL test fixtures in these tables;
- a write ordering constraint now exists between `protectionRequestRepository`'s JPA save and `decisionTraceRecorder`'s raw JDBC insert within `decide()` (the former must be flushed, not just saved, before the latter runs) — a subtle dependency that would resurface if a future refactor reordered or removed the explicit flush.

## Guardrails

- neither `accountshield_runtime` nor `accountshield_readonly` owns, or has any grant on, `audit.reject_audit_mutation`, `policy.protect_activated_policy_version`, `recovery.protect_recovery_authorization`, or their triggers — verified by `DatabaseRolePermissionIntegrationTest`;
- `accountshield_runtime` has no `UPDATE`/`DELETE` grant on `audit.decision_trace`/`audit.decision_reason` — verified by the same test;
- the three new foreign keys reject orphan inserts regardless of which role attempts them — verified by new tests in `PersistenceIntegrationTest`;
- `recovery_authorization.decision_id` remains intentionally unconstrained — verified by `RecoveryIntegrationTest.existingAuthorizationWorksWithoutAuditProjection` continuing to pass unchanged.

## Migration/compatibility implications

`V20` only adds roles, grants, default privileges, and foreign keys — no existing column or table is altered or dropped. Several existing test fixtures across `PersistenceIntegrationTest`, `RecoveryIntegrationTest`, `RecoveryConcurrencyTest`, and `RecoveryFlowRetentionCleanupTest` previously inserted `decision_trace`/`recovery_authorization` rows with fabricated (non-existent) parent identifiers as a test-fixture shortcut; these were updated to insert real parent rows first, since the new foreign keys now correctly reject what they were doing.

## Revisit criteria

This decision should be revisited when:

- a `@DynamicPropertySource`-based split-datasource test harness is deliberately built (e.g. as its own dedicated hardening effort), making it practical to run the entire integration-test suite under the restricted role directly;
- the read-only role is actually consumed by a real reporting/observability connection;
- a retention job's delete operation ever gains a side effect that makes a duplicate delete unsafe (at which point a distributed lock would become necessary, mirroring #23's `FOR UPDATE SKIP LOCKED` reasoning for the outbox claim).

## Links

- Issue #25
- [ADR 0002](0002-use-postgresql-as-source-of-truth.md) (Postgres as source of truth), [ADR 0010](0010-recovery-trust-boundaries.md) (recovery_authorization framed as distinct from audit evidence), [ADR 0018](0018-idempotency-claim-before-work.md) / [ADR 0023](0023-outbox-claiming-backoff-and-dead-letters.md) (the retention-job shape this extends)
- Tests: `DatabaseRolePermissionIntegrationTest`, `PersistenceIntegrationTest` (new FK-violation cases), `RecoveryAuthorizationRetentionCleanupTest`
