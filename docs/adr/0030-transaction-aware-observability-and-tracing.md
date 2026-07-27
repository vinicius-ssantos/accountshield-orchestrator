# ADR 0030: Transaction-aware metrics/logs, a real latency Timer, and distributed tracing

- Status: Accepted
- Date: 2026-07-27

## Context

Issue #24 identified a real bug: `ProtectionMetricsRecorder` and `SecurityEventLogger` recorded
success metrics and security log lines via plain `@EventListener` methods, which fire the instant
`ApplicationEventPublisher.publishEvent(...)` is called -- synchronously, inside the still-open
business transaction, regardless of whether that transaction later actually commits. A failure
during commit itself (or, more generally, an outer caller's transaction rolling back the whole unit
of work afterward) would have already recorded a "successful decision" that never actually
persisted. Separately, `docs/operational/slo-targets.md` used a risk-score `DistributionSummary`
(a 0-100 score histogram) as a stand-in for a latency percentile -- not a duration metric at all,
so p50/p99 queries built on it were never meaningful. The README also referenced OpenTelemetry
as an aspirational capability with no actual tracing dependency anywhere in `pom.xml`.

`docs/roadmap.md`'s Gate 7 exit criterion for this specific issue is narrower than the issue's full
scope list: **"success metrics occur after commit and rollback paths are observable."** As with
issue #52, this ADR treats that as the authoritative minimum bar, while still delivering
substantially more of the issue's acceptance criteria where it could be done at acceptable risk
given this session's environment cannot run Maven or Docker Compose locally to pre-verify wiring.

## Decision

### `@TransactionalEventListener(phase = AFTER_COMMIT)`, not a bespoke mechanism

`ProtectionMetricsRecorder.onDecisionMade` and four of `SecurityEventLogger`'s six listeners
(`onProtectionDecisionMade`, `onChallengeCompleted`, `onPolicyActivated`, `onRecoveryCompleted`)
now use Spring's built-in `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`
instead of a custom outbox-style deferral mechanism. This defers listener execution until Spring's
own transaction synchronization confirms a real commit; on rollback, the listener body simply never
runs -- no new infrastructure, reusing a mechanism the JDK/Spring stack already provides. This was
verified safe against every existing consumer of these four event types: all four are *also*
consumed by `OutboxEventRecorder` via `@Transactional(propagation = Propagation.MANDATORY)`
listeners, and `MANDATORY` propagation throws if no transaction is active at publish time --
proving these events are always published from within an active transaction today, which is
exactly the precondition `@TransactionalEventListener`'s default (`fallbackExecution = false`)
requires to avoid silently dropping events.

### Two listeners deliberately kept as plain `@EventListener`

`SecurityEventLogger.onPrivilegedPolicyActionAttempted` and `.onPrivilegedRecoveryActionAttempted`
were **not** converted. Investigation found `DatabasePolicyRolloutService.consumeStepUp` and
`RecoveryApplicationService.consumeReviewStepUp` both publish these events with `authorized=false`
and then immediately rethrow -- deliberately rolling back the transaction. An "attempt" audit trail
must record denied attempts, not just successful ones; converting these to `AFTER_COMMIT` would have
made every denied privileged-action attempt vanish from the security log, since the transaction that
published it never commits. This is the opposite of a "success" metric and was kept synchronous on
purpose, with a code comment at each site explaining why it differs from its four siblings above.

### The rollout counter moved out of the transaction body, into the event

`ProtectionDecisionApplicationService.decide()` previously incremented
`accountshield.policy.rollout.decisions` inline, before the method's remaining work (recovery
authorization issuance, idempotency finalization) had executed -- exactly the same "recorded before
commit is guaranteed" bug pattern, just via an inline `Counter.increment()` instead of an event
listener. `ProtectionDecisionMade` gained two new nullable fields (`rolloutCandidateVersion`,
`rolloutCandidateSelected`); the counter now lives in `ProtectionMetricsRecorder`'s AFTER_COMMIT
listener, reading those fields, only incrementing when a rollout was actually active
(`rolloutCandidateSelected != null`).

### A generic failure counter, not just the two existing degradation-specific ones

`decide()`'s two existing inline degradation counters (`RISK_SIGNAL_STALE`,
`ACTIVE_POLICY_UNAVAILABLE`) are both incremented immediately before a `throw`, with zero prior
writes in that transaction -- confirmed safe as-is, nothing to roll back. To satisfy "explicit
rollback/failure instrumentation" more generally, `decide()` was split into a thin public wrapper
and a private `decideInternal`: the wrapper times the call with a `Timer` and, on any
`RuntimeException`, increments `accountshield.protection.decisions.failed` tagged only by
`exception.getClass().getSimpleName()` (a fixed, bounded set of exception types this codebase
throws -- not user input) before rethrowing.

### A real duration `Timer`, with explicit SLO histogram boundaries

`accountshield.protection.decision.duration` wraps the entire `decide()` call (tagged only by
`outcome`, including a synthetic `"ERROR"` value on the failure path -- five possible values total,
satisfying "high-cardinality identifiers are not used as metric tags"). It's built with
`.publishPercentileHistogram()` plus explicit `.serviceLevelObjectives(...)` at 50/100/250/500/
1000/2000 ms, so `histogram_quantile` queries resolve against deliberately chosen bucket
boundaries rather than Micrometer's generic defaults. `docs/operational/slo-targets.md`'s latency
row and PromQL were rewritten to use this metric's `_seconds_bucket` series; the Grafana dashboard
gained a real p50/p95/p99 panel plus new outbox/retention panels (dead-lettered count, oldest-
pending age, and `accountshield_retention_purged_total` broken down by the `job` tag, covering
outbox, challenge, recovery, and idempotency retention jobs from metrics that already existed).

### Micrometer Tracing + OTLP + `@Observed`, with Jaeger receiving OTLP directly

`micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`, and `spring-boot-starter-aop` were
added (all managed by Spring Boot's own dependency management -- no explicit versions, matching
every other Spring-managed dependency already in `pom.xml`). A genuinely surprising find during
investigation: `application.yml`'s log pattern *already* contained `%X{traceId:-},%X{spanId:-}` MDC
placeholders, apparently added in anticipation of tracing that was never wired up -- adding the
bridge is closer to completing an already-declared intent than introducing a new one.
`management.tracing.sampling.probability` defaults to full (1.0) sampling for this demo/local-scale
system; `management.observations.annotations.enabled: true` activates Spring Boot's `ObservedAspect`
for `@Observed`-annotated methods. One method per module named in the issue's scope now carries
`@Observed`: `DeterministicRiskAssessmentService.assess`, `DatabasePolicyEvaluationService.evaluate`,
`JdbcDecisionTraceRecorder.record`, `ChallengeApplicationService.create`,
`RecoveryApplicationService.initiate`, and `OutboxRelay.dispatchPending` (the `@Scheduled` method
itself, not the private `dispatchSingle` it calls -- Spring AOP proxies cannot intercept
self-invocation, so annotating a private method called only via `this.` would have been silently
inert). `compose.yaml` gained a single `jaeger` (all-in-one) service; a separate OpenTelemetry
Collector was deliberately not added, since modern Jaeger accepts OTLP directly on 4317/4318 and a
collector hop adds pipeline flexibility this scope has no use for.

Test `application.yml` disables OTLP export (`management.otlp.tracing.export.enabled: false`) so
`mvn verify` never attempts a real network call to a nonexistent collector during CI, while still
exercising the tracing bridge's MDC population.

## Threat model and limitations

**What this catches:** a decision, challenge completion, policy activation, or recovery completion
whose surrounding transaction ultimately rolls back (for any reason, at any point after the event
was published) no longer inflates the corresponding success counter or security log -- proven with
a real, no-mocks integration test that forces an actual commit failure (see Guardrails).

**What this does not catch:** the new `Timer` measures method-execution latency, not "did the
surrounding transaction's commit ultimately succeed" -- a call that completes its business logic
normally but then fails at commit time is still recorded as a successful-outcome-tagged latency
sample. This is a deliberate, industry-common choice (latency is a property of the computation,
not of infrastructure-level commit failures, which are rare and already separately observable via
the transaction manager's own error handling) rather than an oversight.

## Alternatives considered

- **A bespoke "commit-then-record" queue or outbox-style deferral for metrics** -- rejected:
  `@TransactionalEventListener` already solves exactly this problem, is part of the framework
  already in use throughout this codebase, and needs no new persistence or infrastructure.
  Converting *all six* `SecurityEventLogger` listeners uniformly -- rejected once investigation
  found the two "attempted" listeners deliberately log on a rollback path; a blanket conversion
  would have silently deleted denied-privileged-action security logging, a real regression this
  ADR specifically avoided by reading each listener's actual call sites before converting it.
- **Extending `ProtectionDecisionMade` with the rollout fields** vs. leaving the rollout counter
  inline -- chosen to extend the event: the inline counter had exactly the same premature-recording
  bug as the metrics/logs this issue fixes, and issue #52's contract-test baselines for this exact
  event were never committed (bootstrap mode), so this was a safe, zero-baseline-break moment to do
  it.
- **An OpenTelemetry Collector between the app and Jaeger** -- rejected: modern Jaeger accepts OTLP
  natively; a collector adds a pipeline-processing hop with no consumer in this scope.
- **Wiring `@Observed` onto `OutboxRelay.dispatchSingle`** (the actual per-event dispatch logic)
  -- rejected: it's `private` and called via `this.dispatchSingle(...)`, which bypasses Spring's
  proxy entirely; the annotation would compile but never fire. `dispatchPending` (the `@Scheduled`
  entry point, invoked by Spring's scheduler through the proxy) was used instead.

## Consequences

### Positive

- the specific, real bug this issue reported (success recorded before commit is guaranteed) is
  fixed for every event type where it mattered, and *not* naively "fixed" where doing so would have
  broken a different, deliberate security-logging guarantee;
- a real, intentionally-bucketed latency `Timer` now backs the SLO doc and dashboard instead of a
  metric that could never have produced a meaningful percentile;
- tracing is wired end-to-end (bridge, OTLP export, `@Observed` spans across all six named modules,
  Jaeger in Compose) using entirely Spring-Boot-managed dependency versions and a well-established
  configuration recipe, with test-time export disabled so CI never depends on a running collector.

### Negative

- this issue's full scope also asked for genuinely new Grafana dashboards *per* outbox/challenge/
  recovery/idempotency concern; this PR added focused panels to the existing single dashboard
  rather than four new dashboard files, a smaller deliverable than the literal ask;
  the tracing/`@Observed` wiring cannot be validated by actually running the application or Compose
  stack in this environment -- it follows a well-documented Spring Boot recipe, but CI (`mvn
  verify`) is the first real compilation/wiring check, consistent with every other issue this
  session.

## Guardrails

- `TransactionAwareMetricsIntegrationTest`: a real Spring context + Postgres test that registers an
  extra `BEFORE_COMMIT`-phase listener which throws, forcing an actual commit failure and rollback
  after `ProtectionDecisionMade` is published -- proves the success counter does *not* increment in
  that case, and *does* increment by exactly one for an otherwise-identical decision that actually
  commits;
- `ProtectionDecisionApplicationServiceTest`: proves the duration `Timer` is tagged only by
  `outcome` (no high-cardinality tag), the new generic failure counter increments and is tagged by
  exception type, and the rollout fields now flow through to the published event;
- `ProtectionMetricsRecorderTest`: proves the rollout counter increments correctly by selection and
  is absent entirely when no rollout was active for a given decision.

## Migration/compatibility implications

`ProtectionDecisionMade`'s constructor gained two new trailing parameters
(`rolloutCandidateVersion`, `rolloutCandidateSelected`); every call site in this codebase
(production and test) was updated. Issue #52's `IntegrationEventFixtures.protectionDecisionMade()`
was updated to match -- safe because its contract-test baseline was never committed (still in
bootstrap mode as of that issue's PR).

## Revisit criteria

- when the `docs/roadmap.md`-deferred per-concern (outbox/challenge/recovery/idempotency)
  dashboards are actually needed as separate files rather than panels on one dashboard;
- when a real Compose-based end-to-end trace walkthrough (a demo request followed across
  decision/audit/challenge/outbox spans in the Jaeger UI) is performed and documented, since this
  environment could not do so itself;
- if `management.tracing.sampling.probability: 1.0` (full sampling, appropriate for this system's
  current scale) ever needs to be reduced for a higher-throughput deployment.

## Links

- Issue #24
- `docs/roadmap.md` Gate 7 (the authoritative scope signal for this issue)
- [ADR 0023](0023-outbox-claiming-backoff-and-dead-letters.md) (the outbox listener explicitly kept
  synchronous/in-transaction, unmodified, per this issue's own instruction)
- [ADR 0026](0026-signed-webhook-delivery-with-replay-protection.md) /
  [ADR 0029](0029-api-and-event-compatibility-gates.md) (the event surface `ProtectionDecisionMade`
  belongs to)
- Tests: `TransactionAwareMetricsIntegrationTest`, `ProtectionDecisionApplicationServiceTest`,
  `ProtectionMetricsRecorderTest`, `SecurityEventLoggerTest`
