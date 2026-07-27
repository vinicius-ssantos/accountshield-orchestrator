# Capacity and performance benchmark methodology

This page documents how AccountShield's reproducible capacity benchmark (issue #50, ADR 0035)
works, not the numbers themselves. The numbers are never hand-copied into this file, because a
hardcoded number goes stale the moment code, dependencies, or the runner's hardware change --
instead every run produces a fresh Markdown report, uploaded as a build artifact, that always
reflects the exact commit and environment it came from.

## Where the numbers actually are

- **Every pull request**: `benchmark-report` artifact on the `verify` job in `ci.yml`, containing
  `capacity-smoke.md` -- a tiny (10-call) smoke run proving the harness itself works, with no
  latency/throughput threshold asserted (a single shared CI runner is too noisy to gate on).
- **Every night**: `nightly-benchmark-report` artifact on `nightly.yml`'s `full-verify` job,
  containing the full measurement-grade suite: `capacity-benchmark.md`,
  `persistence-and-growth-benchmark.md`, `outbox-publish-benchmark.md`, and
  `connection-pool-saturation.md`.
- **Locally**, against a real Postgres instance, for hardware-specific numbers:
  ```
  ./mvnw -Dgroups=benchmark test
  ```

Each report's own "Environment" section states the exact Java version, OS, available processors,
max JVM heap, and database used for that specific run -- required context, since these numbers are
meaningless without it (roadmap.md Gate 7: "capacity claims include environment and p50/p95/p99
evidence").

## What is measured, and where

All 8 dimensions issue #50 names, run against the real Spring context and a real Postgres instance
(Testcontainers) -- no mocks:

| # | Dimension | Test class | Package |
|---|---|---|---|
| 1 | Decision throughput and latency (sequential + concurrent) | `CapacityBenchmarkTest` | `benchmark` |
| 2 | Policy evaluation cost (isolated) | `CapacityBenchmarkTest` | `benchmark` |
| 3 | Persistence latency (raw single-row insert) | `PersistenceLatencyBenchmarkTest` | `protection.internal.persistence` |
| 4 | Outbox publish throughput | `OutboxPublishThroughputBenchmarkTest` | `outbox.internal` |
| 5 | Replay throughput | `CapacityBenchmarkTest` | `benchmark` |
| 6 | Database growth and index impact | `PersistenceLatencyBenchmarkTest` | `protection.internal.persistence` |
| 7 | Connection-pool saturation | `ConnectionPoolSaturationTest` | `benchmark` |
| 8 | Audit/hash-chain verification overhead | `CapacityBenchmarkTest` | `benchmark` |

Dimensions 3, 4, and 6 live outside the `benchmark` package because they need direct access to a
module's own `internal` repository/relay classes (`ProtectionRequestRepository`, `OutboxRelay`) --
matching this codebase's existing convention of internal-module tests living inside that module's
own `internal` test package (e.g. `OutboxReclaimAfterProcessFailureTest`), rather than exposing
those internals outside the module. The shared `BenchmarkStats` (percentile/throughput/error-rate
computation) and `BenchmarkReport` (Markdown rendering + environment section) classes are `public`
in the `benchmark` package specifically so every dimension's test class can reuse them regardless
of which package it lives in.

## Test tagging: default CI vs. nightly

Every benchmark test class except `CapacitySmokeBenchmarkTest` is `@Tag("benchmark")` --excluded
from the default CI gate (`ci.yml`'s `-DexcludedGroups=resilience,benchmark`) the same way
`@Tag("resilience")` fault-injection tests are (ADR 0032), and run in full every night
(`nightly.yml`, which passes no `-DexcludedGroups`). This satisfies "CI runs a smoke benchmark
without flaky hard thresholds": the untagged `CapacitySmokeBenchmarkTest` runs on every PR and
asserts only that the harness completes without error, never a specific number; the real
measurement-grade numbers are nightly-only, where noisy shared-runner timing doesn't block a PR.

## Numbers are wall-clock, not statistically isolated

Every number is a single wall-clock run on that CI/nightly runner's shared hardware -- not an
isolated, repeated-trial statistical benchmark with warm-up exclusion or outlier trimming. Treat
them as directional evidence of shape and relative cost (e.g. "policy evaluation is roughly 20x
cheaper than a full decision"), not as SLA-grade absolute numbers a production capacity plan should
be sized against without independent, dedicated-hardware verification.

## The measured bottleneck

Issue #50 requires "the report identifies at least one measured bottleneck."
`ConnectionPoolSaturationTest` (dimension 7) is built specifically to produce one reliably: it runs
its own Spring context with `spring.datasource.hikari.maximum-pool-size` shrunk to 3 (via
`@DynamicPropertySource`, the same per-test datasource-override pattern
`DatabaseLatencyResilienceTest` already established for Toxiproxy, ADR 0032), then compares
decision latency at concurrency == pool size (no queueing expected) against concurrency = 4x pool
size (queueing expected). The comparison isolates connection-pool contention as a real, directly
attributable latency cost, bounded safely by the default 5s `connection-timeout` so the demonstration
never flakes into a hard connection-acquisition failure at this call volume.

## Reproducing locally

```
./mvnw -Dgroups=benchmark test
```

against a real Postgres instance (Testcontainers, no other setup required -- Docker must be
running). Reports land in `target/benchmark-reports/*.md`.
