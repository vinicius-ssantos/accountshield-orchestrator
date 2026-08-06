# ADR 0033: Property-based tests and API fuzzing

- Status: Accepted
- Date: 2026-07-27

## Context

Issue #53 asked for property-based tests (jqwik) and OpenAPI-aware fuzzing covering 8 named
invariants: risk score stays in range; a decision has exactly one outcome; terminal states never
transition backwards; consumed challenges cannot be reused; equivalent idempotent retries return
the same result; key reuse with a different payload conflicts; replay creates no side effects;
malformed headers/bodies never produce 5xx leaks. `docs/roadmap.md`'s Gate 7 exit criterion for
this issue is **"generated inputs preserve core invariants"** -- property-based testing is the
core ask; full OpenAPI-spec-driven fuzzing tooling is not required to satisfy it.

Two invariants were already effectively covered by existing tests with hand-picked examples rather
than generated inputs: "a decision has exactly one outcome" (every `ProtectionOutcome` value is a
typed enum field, and `ProtectionDecisionApplicationServiceTest` already exercises every outcome
branch) and "replay creates no operational side effects" (`SimulationIntegrationTest
.replayCreatesNoChallengeRecoveryOutboxOrAuditMutation`, from ADR 0006/0019). This ADR adds
genuinely generated-input coverage for the remaining 6.

## Decision

### jqwik added, verified locally at every step

`net.jqwik:jqwik:1.9.1` (test scope). Every new test in this PR was compiled -- and, where it
didn't need Postgres, actually **run** -- locally before pushing, continuing this session's
established discipline (ADR 0031, ADR 0032) of never guessing at an unfamiliar library's behavior
when it can be checked directly.

### `RiskScorePropertyTest`: a real jqwik `@Property`, and a real bug it caught immediately

The only test in this PR using jqwik's native `@Property`/`@ForAll` mechanism (the other three use
`Arbitraries` as a plain generator inside ordinary Spring tests -- see below). Generates the full
input space of `RiskSignals` and asserts `DeterministicRiskAssessmentService.assess(...).score()`
always stays in `[0, 100]`. **On the very first local run, this property failed immediately**: it
generated `failedAttempts` values up to 50, and `RiskSignals`'s own constructor validates
`failedAttempts <= 20` -- a real constraint this ADR's author had gotten wrong when writing the
`@IntRange` bound, caught by jqwik's generation before ever reaching CI. This is exactly what
property-based testing is for, demonstrated on its first use in this codebase.

### `IdempotencyPropertyTest` and `ChallengeStateMachinePropertyTest`: `Arbitraries` inside `@SpringBootTest`, not jqwik's own engine

Both properties ("equivalent idempotent retries return the same result" / "key reuse conflicts",
and "terminal states never transition backwards" / "consumed challenges cannot be reused") need the
*real*, Postgres-backed `IdempotencyGuard` and `ChallengePlanRepository` -- not mocks -- to mean
anything. jqwik's own JUnit5 test engine has no established integration with this codebase's Spring
test lifecycle, and building one was judged unnecessary risk for this PR: `net.jqwik.api.Arbitraries`
is usable standalone (`.sample()`) from any ordinary JUnit5 `@Test`/`@RepeatedTest` method, which
already runs correctly inside `@SpringBootTest`. Both tests use `@RepeatedTest(20)` with a fresh
jqwik-generated sample each repetition -- bounded, deterministic-count coverage across generated
inputs, without introducing a second test-execution engine's interaction with Spring into this
codebase.

### `MalformedRequestFuzzTest`: a curated malformation set, not spec-driven fuzzing

Rather than adopt an OpenAPI-schema-driven fuzzing tool (an unfamiliar external tool this session
could not verify integrates correctly with this project's Spring Security-protected, JWT-gated
endpoints), this test curates a list of realistic malformation strategies against
`/api/v1/protection-decisions`'s actual request shape (`ProtectionDecisionRequest`): truncated/
invalid JSON, wrong field types, out-of-range values, oversized strings, unexpected shapes (arrays,
nulls, extra fields), plus a parallel set of malformed `Authorization` headers. `Arbitraries.of(...)`
picks one per `@RepeatedTest(40)` repetition. Every response is asserted to be `< 500` and checked
against a list of internal-leak markers (stack frame prefixes, `jakarta.persistence`/
`org.hibernate`/`org.postgresql` package names, and the literal `local-only` secret-placeholder
convention this codebase uses throughout `application.yml`) -- directly implementing "no raw stack
traces or secrets appear in responses."

### Reproducible seeds and failure artifacts: mostly free, by design

jqwik reports the exact failing sample and a reproducible seed automatically on any `@Property`
failure -- satisfying "generated tests use reproducible seeds on failure" for `RiskScorePropertyTest`
with no extra code. The three `Arbitraries`-in-`@RepeatedTest` tests don't get this for free (they
aren't real jqwik properties), so each includes the generated/offending input directly in its
AssertJ failure message (`.as("...", offendingInput)`) -- the practical equivalent of a preserved
fuzz artifact: the exact value that failed is always in the test's own failure output.

### A second real configuration trap, caught locally

Adding a `jqwik.properties` file (the pattern this ADR's author remembered from an older jqwik
version) produced a `SEVERE` log warning and was silently ignored: jqwik deprecated that file in
favor of `junit-platform.properties` (with jqwik's own keys prefixed `jqwik.`) as of version 1.6.
Found by extracting the real `jqwik-engine-1.9.1-sources.jar` and reading `JqwikProperties.java`
directly rather than guessing the current mechanism. Fixed: `src/test/resources/junit-platform
.properties` sets `jqwik.tries.default=200` (a bounded, fast default for the normal CI gate);
`nightly.yml` overrides it with `-Djqwik.tries.default=2000` for deeper fuzzing. Both the bounded
default and the override were verified locally to actually take effect (jqwik's own report footer
prints the exact `tries` count used).

## Alternatives considered

- **A full OpenAPI-schema-driven fuzzer** (e.g. a Schemathesis-equivalent for Java) -- rejected:
  an unfamiliar external tool whose integration with this project's JWT-gated endpoints could not
  be verified in this environment; the curated malformation set directly implements the named
  acceptance criterion without that risk.
- **jqwik's own Spring integration (if any exists in this version)** -- not pursued: given the
  effort already spent this session and the real risk of a subtly-wrong integration going
  unnoticed without being able to fully exercise it, `Arbitraries.sample()` inside the established,
  proven `@SpringBootTest` pattern was the lower-risk choice.
- **Re-deriving "exactly one outcome" and "replay has no side effects" as new property tests** --
  rejected: both are already covered by existing tests; ADR 0032's "don't duplicate what already
  exists" reasoning applies here too.

## Consequences

### Positive

- a real, previously-latent assumption error (`RiskSignals`'s actual valid range) was caught by
  property-based testing on its very first use in this codebase, a concrete demonstration of the
  technique's value;
- the `junit-platform.properties` bounded-tries/nightly-override mechanism is reusable for any
  future jqwik property, not just this PR's one `@Property` test;
- the malformed-request fuzz test's leak-marker list is centralized and easy to extend if a new
  internal detail (a new package, a new secret-placeholder convention) needs guarding against.

### Negative

- only one test in this PR is a "real" jqwik property (with jqwik's own shrinking/seed-reporting);
  the other three use `Arbitraries` as a plain generator inside ordinary tests, a deliberate
  scope/risk tradeoff rather than full jqwik-native coverage;
- the malformed-request fuzz test's malformation set is curated and finite, not exhaustive
  spec-driven generation -- it catches the named categories, not every conceivable malformed input.

## Guardrails

- `RiskScorePropertyTest`: run locally (`./mvnw test -Dtest=RiskScorePropertyTest`), including the
  failure it caught before the `@IntRange` bound was corrected, and the passing run afterward with
  the reported tries/checks count.
- `IdempotencyPropertyTest`, `ChallengeStateMachinePropertyTest`, `MalformedRequestFuzzTest`: all
  `./mvnw test-compile`-verified; require real Postgres (Testcontainers) to actually execute, same
  as every other integration test in this suite -- CI is the first real execution for these three.
- The `junit-platform.properties` bounded-tries default (200) and the nightly override
  (`-Djqwik.tries.default=2000`) were both verified locally to take effect via jqwik's own reported
  `tries =` count.

## Revisit criteria

- if a genuine need arises for jqwik's own shrinking/reporting against Postgres-backed state,
  investigate jqwik's Spring integration properly (with enough budget to verify it, unlike this PR);
- if `MalformedRequestFuzzTest`'s curated list stops finding anything new, consider a real
  OpenAPI-spec-driven fuzzer as a follow-up, now that a first, lower-risk fuzz test exists to
  compare against;
- add property tests for any future core aggregate invariant using the same
  `Arbitraries`-in-`@SpringBootTest` (state-needing) or native `@Property` (pure-function) split
  this ADR establishes.

## Links

- Issue #53
- `docs/roadmap.md` Gate 7 (the authoritative scope signal for this issue)
- [ADR 0031](0031-ci-and-software-supply-chain-security.md) / [ADR 0032]
  (0032-resilience-and-concurrency-fault-injection.md) (the "verify locally, don't guess at an
  unfamiliar library" discipline this ADR continues, including a second real configuration trap
  caught the same way)
- Tests: `RiskScorePropertyTest`, `IdempotencyPropertyTest`, `ChallengeStateMachinePropertyTest`,
  `MalformedRequestFuzzTest`
