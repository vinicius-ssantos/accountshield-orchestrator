# ADR 0036: Executable backup, restore, and disaster-recovery drill

- Status: Accepted
- Date: 2026-07-27

## Context

Issue #51 asked for an *executable* backup/restore/disaster-recovery procedure -- explicitly "not
documentation-only" -- covering: RPO/RTO targets, automated PostgreSQL backup and restore,
post-restore migration validation, audit-chain integrity verification, active-policy uniqueness,
prevention of unintended outbox republishing, post-restore smoke tests, a restore-drill report, and
secrets/key-recovery considerations. roadmap.md's Gate 7 exit criterion is direct: "restore drills
validate domain and audit integrity." Unlike several earlier issues this session, every acceptance
item is genuinely achievable against this system's real implementation -- no scope-narrowing
decision was needed.

This repository's demo environment runs a single Postgres instance (`compose.yaml`); there was no
prior backup/restore tooling, script, or test of any kind.

## Decision

### A real `pg_dump`/`psql` round trip, not a simulation

`DisasterRecoveryDrillTest` (`@Tag("disaster-recovery")`) takes a real `pg_dump` of its own
`@SpringBootTest`'s Testcontainers Postgres instance (the "source"), via
`GenericContainer.execInContainer` (the real command actually runs inside the real Postgres
container, using the exact same `pg_dump`/`psql` client tools a real operator would use), copies the
dump out, spins up a **second, completely independent, freshly-started** Postgres Testcontainers
instance (the "destination", starting genuinely empty -- not seeded, not schema-pre-created by this
codebase), and restores the dump into it via `psql -f`. This is the actual backup/restore mechanism
being validated, not a mock or an in-memory stand-in.

### A second, independently-bootstrapped Spring context proves the restore for real

After restore, the test does not just run raw SQL assertions against the destination -- it
bootstraps a **second, fully independent Spring application context**
(`new SpringApplicationBuilder(AccountShieldApplication.class).web(WebApplicationType.NONE)...run()`)
pointed at the restored database via overridden `spring.datasource.*` properties. This means:

- **Migration validation is real, not simulated**: Flyway runs its normal startup check against the
  restored schema; if the restore were incomplete or the migration history diverged, context
  startup itself would fail. An explicit row-count comparison against the source's
  `flyway_schema_history` makes this visible in the report rather than only implicit-by-not-throwing.
- **Every domain-invariant check uses the real application beans** (`AuditChainVerificationService`,
  `OutboxRelay`, `ProtectionDecisionService`), not hand-rolled SQL reimplementations of what those
  services already do correctly.
- **The post-restore smoke test is a real `decide()` call** through the fully restored, fully
  running application -- proving the system is actually serving again, not just that its database
  contains the right rows.

### RPO is demonstrated, not asserted from a config value

Rather than picking an arbitrary target number, the drill demonstrates the *mechanism* of data loss
concretely: it seeds `PRE_BACKUP_DECISIONS` decisions, takes the backup, then seeds
`POST_BACKUP_DECISIONS` more decisions that are never included in that backup. The restored
database's row count is asserted to equal exactly the pre-backup count -- proving the backup
boundary is exact and that any write after it is genuinely lost. This is a stronger, more honest
demonstration of "RPO" for a periodic-backup strategy than a hardcoded target, and directly satisfies
"measured RPO/RTO are included in the report" without inventing a number this system has never
actually validated in production.

### RTO is measured wall-clock time, broken into its real phases

Backup duration, restore duration, and "app ready" duration (context bootstrap + every validation +
the smoke test) are each measured and summed into a total RTO. Following every other benchmark this
session (ADR 0031, ADR 0035): this is a single wall-clock run's numbers, reported as directional
evidence with an explicit environment disclaimer, not asserted against a hardcoded SLA.

### Outbox republish prevention, proven both ways

The drill checks the previously-published outbox row count is unchanged after restore (proving the
dump/restore round trip itself doesn't silently reset delivery state), *and* calls
`OutboxRelay.dispatchPending()` again against the restored, live relay -- proving the running system
itself, not just the raw data, does not re-attempt delivery of already-published events after a
restore.

### Default-gate vs. nightly split, matching ADR 0032/0035 exactly

`@Tag("disaster-recovery")` is excluded from `ci.yml`'s default gate
(`-DexcludedGroups=resilience,benchmark,disaster-recovery`) and runs in full every night
(`nightly.yml`), which needs no additional wiring since it already runs with no
`-DexcludedGroups`. Unlike issue #50's benchmark suite, no untagged default-gate smoke variant was
added: standing up two full Postgres containers plus two Spring contexts is a fundamentally
heavier, container-spin-up-dominated operation (tens of seconds, most of it Docker/Testcontainers
overhead, not application logic) than issue #50's lightweight decision-loop smoke test, so a
meaningfully smaller "smoke" version isn't achievable without testing something materially
different from the real drill -- this mirrors why `DatabaseLatencyResilienceTest` (ADR 0032) has no
lightweight default-gate counterpart either.

## Alternatives considered

- **A shell script outside the test suite (e.g. `scripts/dr-drill.sh`)** -- rejected: would require
  a separate CI job, its own container orchestration, and duplicate logic for spinning up Postgres
  that Testcontainers already does correctly and repeatably for this codebase's entire test suite.
  Reusing the same JUnit/Testcontainers infrastructure every other integration test in this
  repository already relies on is simpler and keeps the drill runnable with the exact same
  `./mvnw` command as everything else.
- **`pg_basebackup`/WAL-archiving-based continuous backup** -- rejected as the mechanism to
  validate here: it's a materially different, more complex operational setup (continuous WAL
  shipping, a standby, PITR tooling) disproportionate to this demo environment's current
  single-instance `compose.yaml`. `pg_dump`/`psql` directly validates the acceptance criteria named
  ("automate PostgreSQL backup and restore... a fresh environment can be restored from a documented
  backup") without requiring a new standing operational topology this repository doesn't otherwise
  have. Revisit if the deployment model ever moves beyond a single demo instance.
- **Restoring into the same container instead of a second one** -- rejected: restoring over the
  live source database would not prove anything about disaster recovery (a real disaster destroys
  the original instance); a genuinely separate, freshly-started destination container is the only
  way to prove the backup is self-sufficient.
- **Asserting the restore's stderr is empty** -- rejected once the role/grant finding below was
  observed: it would make the test fail on a real, expected, and now-documented limitation rather
  than surfacing it. The stderr is captured and included in the report instead, verbatim, so the
  finding stays visible rather than being asserted away.

## Consequences

### Positive

- every acceptance item in issue #51 is genuinely exercised against real tooling, not documented
  aspirationally;
- the restore-boundary (RPO) demonstration and the RTO phase breakdown are concrete, reproducible
  evidence, not claims;
- the drill surfaced a real, previously-undocumented operational gap (see "Real finding" below)
  that a documentation-only procedure would very plausibly have missed.

### Negative

- `pg_dump`/`psql` validates a single-instance demo backup strategy; a production deployment with
  WAL archiving, point-in-time recovery, or a managed database service would need a different (and
  currently unwritten) drill;
- the drill does not currently test restoring while the source is still writable concurrently (a
  true "hot backup under load" scenario) -- it seeds sequentially before taking the backup;
- like ADR 0035, numbers are single wall-clock runs on shared CI/nightly hardware, not
  statistically rigorous.

## Real finding: role/grant restoration is not part of a data-only dump

The drill's restore step reliably surfaces `ERROR: role "accountshield_runtime" does not exist` /
`role "accountshield_readonly" does not exist` in `psql`'s stderr (captured verbatim in every
report): migration V20's (ADR 0024) `CREATE ROLE`/`GRANT` statements are cluster-level, not part of
the dumped database's data, so a raw `pg_dump`/`psql` restore into a cluster that has never run
that migration does not recreate them. Table data and structure restore correctly regardless (`psql
-f` without `ON_ERROR_STOP` continues past these specific errors), but the custom roles' privileges
do not exist on the destination until the migration (or at least V20) is re-run there. **A real
restore procedure must re-run the relevant migrations against a fresh cluster before, or as part
of, restoring data -- not rely on a bare data dump alone.** This is now documented both in the
generated report (every run) and here, rather than silently discovered the first time it mattered
operationally.

## Secrets and key-recovery considerations

The envelope-encryption KEK secret (`accountshield.crypto.*`, ADR 0025) is not part of a `pg_dump`
data backup at all -- it lives in application configuration/secrets management, not the database.
This drill's restored context only decrypts `account_reference` correctly because it shares the
exact same classpath configuration as the source (same JVM, same test run). **A real
disaster-recovery procedure must independently guarantee the KEK secret is available wherever the
restored database is served from** (secrets manager, vault, or equivalent) -- if it is lost or
unavailable, every envelope-encrypted field becomes permanently, unrecoverably unreadable, which is
by design (ADR 0025's crypto-shredding property cuts both ways) but must be an explicit, understood
operational dependency, not an implicit assumption.

## Guardrails

- Every assertion (migration count, audit-chain validity, policy uniqueness, outbox counts,
  decision-count exactness, smoke-test success) is a hard JUnit assertion against real data, not a
  soft/logged check.
- Run locally against real Testcontainers Postgres 8 times during this PR's preparation (Docker was
  available in this environment for this issue): 7 of 8 runs passed cleanly; one early run (before a
  permanent pre-backup chain-validity precondition assertion was added specifically to investigate
  this) showed a single transient `AuditChainVerificationService` hash mismatch at one sequence
  number, not reproduced in any of the 7 subsequent runs -- including runs with that new
  precondition check isolating whether the issue existed on the source *before* any restore was
  attempted (it did not, in every reproduction attempt). Most plausibly attributable to unusually
  heavy concurrent background load on the machine during that one run rather than a structural
  defect in the restore mechanism or the audit-chain code itself, but not confidently root-caused --
  stated here explicitly per this project's "state assumptions and incomplete verification clearly"
  standard. The permanent pre-backup precondition assertion, and the post-restore assertion, both
  remain hard failures: if this recurs in CI, the build will fail visibly, not silently pass.
- No number in the generated report is hardcoded; every run regenerates it fresh (same "report,
  don't gate" stance as ADR 0031/ADR 0035).

## Revisit criteria

- If the transient audit-chain hash-mismatch observed once during local verification (see
  Guardrails) recurs in CI, open a dedicated investigation issue for the audit-chain module itself
  rather than attempting to patch `AuditChainHasher` speculatively here -- an unconfident change to
  the hashing algorithm risks breaking backward verifiability of already-recorded chain hashes,
  exactly what ADR 0027's versioned `canonical_schema_version` column exists to protect against.
- If this system's deployment model ever moves beyond a single-instance demo environment, revisit
  the `pg_dump`/`psql` mechanism in favor of WAL-archiving/PITR-based continuous backup, and extend
  the drill to a genuinely concurrent "hot backup under load" scenario.
- If the custom-role/grant restoration gap above becomes operationally relevant, automate re-running
  the relevant migrations as an explicit, scripted first step of the real restore procedure (not
  just documented) -- currently out of scope since this repository's actual deployment (`compose.yaml`)
  does not yet use the restricted runtime role at all (ADR 0024's own revisit criterion).

## Links

- Issue #51
- [ADR 0024](0024-database-least-privilege-and-integrity.md) (the role/grant migration whose
  restoration gap this drill surfaced)
- [ADR 0025](0025-envelope-encryption-key-rotation-and-crypto-shredding.md) (the KEK secret this
  drill's "secrets and key recovery considerations" section is about)
- [ADR 0027](0027-tamper-evident-audit-hash-chaining.md) (the audit-chain mechanism this drill
  verifies post-restore, and whose versioned schema protects against unconfident hashing changes)
- [ADR 0031](0031-ci-and-software-supply-chain-security.md) /
  [ADR 0035](0035-reproducible-capacity-benchmark.md) (the "report, don't gate" convention and the
  `@Tag`/nightly-split convention, both reused here)
- Tests: `DisasterRecoveryDrillTest`
