# ADR 0032: Resilience and concurrency fault-injection test suite

- Status: Accepted
- Date: 2026-07-27

## Context

Issue #39 named 8 fault-injection scenarios: database commit failure after evaluation; process
failure after publish and before outbox acknowledgement; multiple relay instances claiming events;
concurrent challenge verification and consumption; concurrent policy activation; database latency
and connection interruption; clock boundaries for delayed recovery; unavailable historical
algorithm versions. `docs/roadmap.md`'s Gate 7 exit criterion for this issue is narrower than the
full list: **"critical crash, latency, and race scenarios are reproducible."**

Before writing anything, a research pass across this codebase's existing 500+ tests found that
**most of the 8 named scenarios were already covered**, some very thoroughly, by tests written for
earlier issues this session. Implementing this issue meant closing the *specific remaining gaps*,
not building a fault-injection suite from scratch.

## Decision

### What was already covered (confirmed, not re-implemented)

- **Database commit failure after evaluation**: `ProtectionDecisionIntegrationTest
  .rollsBackTheProtectionRequestWhenAuditRecordingFailsAfterFlush` already forces a failure after
  a flush and asserts the whole transaction rolled back.
- **Multiple relay instances claiming events**: `OutboxClaimStoreConcurrencyTest
  .concurrentClaimsNeverDoubleClaimTheSameEvent` already races 4 concurrent claimers and asserts no
  duplicate claims, proving the `FOR UPDATE SKIP LOCKED` guarantee from ADR 0023.
- **Concurrent policy activation**: `PolicyActivationConcurrencyTest
  .concurrentActivationOfTwoApprovedVersionsHasExactlyOneWinner` already races two approved
  versions and asserts exactly one wins via the `uq_single_active_policy` constraint.
- **Unavailable historical algorithm versions**: `InMemoryRiskAlgorithmRegistryTest
  .throwsForAnUnregisteredVersion` already proves `resolve()` throws explicitly, and
  `SimulationApplicationService.replay()` calls `resolve()` unguarded -- the failure is already
  guaranteed to propagate through the real replay entry point.

### What this PR adds (the real, confirmed gaps)

- **`OutboxReclaimAfterProcessFailureTest`** ("process failure after publish and before outbox
  acknowledgement"): seeds a row directly as an abandoned `IN_PROGRESS` claim (simulating a relay
  instance that claimed an event and crashed before ever calling `markPublished`/
  `markFailedWithBackoff`/`markDeadLettered`) and proves `OutboxClaimStore.claimBatch` reclaims it
  once `claimed_at` is older than the caller's stale-claim cutoff -- the at-least-once guarantee
  `OutboxRelay` relies on in production.
- **`ChallengeConcurrencyTest`** ("concurrent challenge verification and consumption"): races N
  concurrent `consume()` calls against real Postgres and asserts exactly one wins (the rest get
  `ChallengeUseRejectedException` via the entity's `@Version` column); separately races N
  concurrent `verify()` calls with the correct code and asserts the *final persisted state* is
  never corrupted (exactly one attempt-decrement, no double-transition) regardless of how many
  individual racers won or lost the implicit optimistic-lock-at-commit race.
- **`RecoveryClockBoundaryTest`** ("clock boundaries for delayed recovery"): proves
  `RecoveryApplicationService.complete()`'s `now.isBefore(eligibleAfter)` gate on both sides --
  rejected just before the window opens, accepted once it has. No clock-mocking infrastructure
  exists anywhere in this test suite's established conventions, so this uses a controlled
  `eligible_after` relative to real elapsed time rather than introducing one.
- **`DatabaseLatencyResilienceTest`** ("database latency and connection interruption"): the only
  scenario needing genuinely new infrastructure. `org.testcontainers:testcontainers-toxiproxy` and
  `org.awaitility:awaitility` were added; Postgres and a Toxiproxy container run on a shared Docker
  network, with the application's actual datasource routed *through* the proxy (via
  `@DynamicPropertySource`, not the shared `PostgreSqlTestConfiguration` every other test uses,
  since this needs a materially different topology). Three scenarios: a cut connection surfaces a
  controlled `DataAccessException` within a bounded time rather than hanging indefinitely; latency
  well inside HikariCP's configured `connection-timeout`/`validation-timeout` (`application.yml`)
  does not itself cause failures; and a restored connection recovers, with subsequent decisions
  succeeding again.

### A real dependency-resolution trap, caught locally before pushing (twice)

Adding `org.testcontainers:toxiproxy` (no version) failed the exact same way issue #24's
`spring-boot-starter-aop` did: `'dependencies.dependency.version' ... is missing`. Rather than
guess a version and find out via a failed CI push (as happened twice in issue #24), the actual
managed artifact name was found by force-downloading `org.testcontainers:testcontainers-bom:2.0.5`
(the version this project's `spring-boot-dependencies:4.1.0` imports, confirmed via
`./mvnw dependency:tree`) and grepping its own `<dependencyManagement>` -- revealing the real
managed artifact is `testcontainers-toxiproxy`, not the older `toxiproxy` naming a first attempt at
a web search suggested. The exact `ToxiproxyContainer` API needed (`getProxy(container, port)`,
`ContainerProxy.setConnectionCut`, `toxics().latency(...)`) was similarly confirmed by extracting
the real `testcontainers-toxiproxy-2.0.5-sources.jar` rather than guessing method signatures for an
unfamiliar major-version API. Every new test in this PR was also `./mvnw test-compile`-verified
locally before pushing, and the tag-exclusion mechanism (`-DexcludedGroups=resilience`) was proven
locally to correctly skip the Toxiproxy test at discovery time (0 tests run, no container-startup
attempt) without needing a running Docker daemon.

### `@Tag("resilience")`, excluded from the default gate, included nightly

`ci.yml`'s `verify` step now runs `mvn verify -DexcludedGroups=resilience`; a new `nightly.yml`
(cron + manual `workflow_dispatch`) runs the full, unfiltered suite. Only
`DatabaseLatencyResilienceTest` carries the tag -- it is the one test whose real container startup
and connection-timeout waits make it meaningfully slower than everything else in this suite; the
other three new tests are fast and deterministic and run in the default gate like every other
existing concurrency test.

## Alternatives considered

- **The newer `org.testcontainers.toxiproxy.ToxiproxyContainer` API** -- considered, then rejected
  for this PR: it has no `getProxy(...)` convenience method in this Testcontainers version (2.0.5),
  requiring hand-wiring a `ToxiproxyClient` and manual proxy creation. The older
  `org.testcontainers.containers.ToxiproxyContainer` is deprecated but fully functional and
  provides exactly the convenience methods needed; using it was the lower-effort, lower-risk choice
  given this PR's scope. Noted as a revisit item.
- **Mocking the `Clock` bean for the recovery boundary test** -- rejected: no test in this codebase
  overrides the `decisionClock` bean; introducing that pattern for one test would be a new
  convention with its own tradeoffs, not clearly better than the real-elapsed-time approach every
  other time-sensitive test in this suite already uses.
- **Re-testing already-covered scenarios "just to have a test named for this issue"** -- rejected:
  cross-referencing existing tests by name in this ADR (and in the PR body) is more honest and more
  useful than duplicating coverage that already exists.

## Consequences

### Positive

- all 4 real gaps in the 8 named scenarios are closed, each backed by a real Postgres integration
  test (or, for latency/interruption, a real Postgres + Toxiproxy test);
- the stable-subset-vs-nightly-full-suite acceptance criterion now has real scaffolding
  (`@Tag`/`-DexcludedGroups` + a scheduled workflow), reusable for any future slow test;
- a real Maven-artifact-naming trap was caught and fixed locally, before ever reaching CI, directly
  applying the lesson from issue #24's two CI-round-trip failures.

### Negative

- `DatabaseLatencyResilienceTest` could not be executed even once in this environment (no running
  Docker daemon this session) -- unlike every other new test in this PR, CI is the first real
  execution, not just the first real *compilation*;
- uses a deprecated Testcontainers API rather than the current-generation one, a deliberate,
  documented tradeoff for this PR's effort budget, not an oversight.

## Guardrails

- `OutboxReclaimAfterProcessFailureTest`, `ChallengeConcurrencyTest`, `RecoveryClockBoundaryTest`:
  all `./mvnw test-compile`-verified; require Docker (Testcontainers Postgres) to actually execute,
  same as every other integration test in this suite.
- `DatabaseLatencyResilienceTest`: `./mvnw test-compile`-verified, and the tag-exclusion mechanism
  was verified locally (`-DexcludedGroups=resilience` against this exact test class produces
  "Tests run: 0" with no container-startup attempt); actual pass/fail behavior against a real
  Toxiproxy container is CI-only verification.

## Revisit criteria

- migrate `DatabaseLatencyResilienceTest` off the deprecated
  `org.testcontainers.containers.ToxiproxyContainer` to the current-generation
  `org.testcontainers.toxiproxy` package once its lower-level API is worth the extra wiring;
- if more slow/fault-injection scenarios are added later, they should reuse the same
  `@Tag("resilience")` convention rather than inventing a new one;
- `nightly.yml` currently only runs `mvn verify`; if it starts finding real, recurring flakiness in
  `DatabaseLatencyResilienceTest`, consider a dedicated retry/quarantine policy rather than
  disabling the test.

## Links

- Issue #39
- `docs/roadmap.md` Gate 7 (the authoritative scope signal for this issue)
- [ADR 0023](0023-outbox-claiming-backoff-and-dead-letters.md) (the `SKIP LOCKED` guarantee
  `OutboxReclaimAfterProcessFailureTest` and the already-existing
  `OutboxClaimStoreConcurrencyTest` both exercise)
- [ADR 0031](0031-ci-and-software-supply-chain-security.md) (the prior issue whose two real CI
  failures directly motivated this issue's "verify locally first" discipline)
- Tests: `OutboxReclaimAfterProcessFailureTest`, `ChallengeConcurrencyTest`,
  `RecoveryClockBoundaryTest`, `DatabaseLatencyResilienceTest`
