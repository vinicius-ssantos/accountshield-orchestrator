# ADR 0014: Explicit degradation strategies for critical-dependency failures

- Status: Accepted
- Date: 2026-07-24

## Context

Issue #44 asked for degradation to be a first-class, auditable domain concept: when a critical dependency (active policy resolution, the database, risk-signal freshness, the challenge provider, audit persistence, the outbox) fails, the system must never accidentally produce `ALLOW`, and the resulting behavior must be explicit, classified, and observable.

Investigation found that most of this already existed, unlabeled:

- **Active policy unavailable** (`policy.ActivePolicyUnavailableException`) already fails closed with `503`/`ACTIVE_POLICY_UNAVAILABLE`; no decision is persisted.
- **Stale risk signal** (`protection.StaleRiskSignalException`, ADR 0013) already rejects with `422`/`STALE_RISK_SIGNAL`; no decision is persisted.
- **Audit-persistence failure** and **outbox failure** both run `@Transactional(propagation = Propagation.MANDATORY)` inside the same transaction as the protection-request write (`JdbcDecisionTraceRecorder`, `OutboxEventRecorder`), so an exception there rolls back the entire transaction — already fail-closed by construction, proven by the existing `ProtectionDecisionIntegrationTest.rollsBackTheProtectionRequestWhenAuditRecordingFailsAfterFlush`.
- **Database unavailability** falls through to Spring Boot's default error handling, which is already generic and non-enumerable without any bespoke code.

The one real gap: **challenge-provider failure**. If `challengeService.create(...)` throws while a policy evaluation says `REQUIRE_STEP_UP`, the exception previously propagated uncaught, rolling back the whole transaction — the caller received a generic `500` indistinguishable from a bug, with no reason code, no metric, and no decision recorded at all.

## Decision

### Classification model

`protection.DegradationStrategy` enumerates the four strategies from the issue text: `FAIL_CLOSED`, `REQUIRE_STEP_UP`, `REQUIRE_MANUAL_REVIEW`, `REJECT_UNAVAILABLE`. `protection.DegradationReason` catalogs the concrete failures this module currently handles, each carrying its strategy and whether it still produces a persisted decision:

| Reason | Strategy | Produces a decision? |
| --- | --- | --- |
| `ACTIVE_POLICY_UNAVAILABLE` | `FAIL_CLOSED` | No |
| `RISK_SIGNAL_STALE` | `REJECT_UNAVAILABLE` | No |
| `CHALLENGE_PROVIDER_UNAVAILABLE` | `FAIL_CLOSED` | Yes |

`REQUIRE_STEP_UP`-as-a-degradation-strategy and `REQUIRE_MANUAL_REVIEW` are declared in `DegradationStrategy` for completeness (matching the issue's own vocabulary) but not wired to any concrete path — no current failure in this codebase naturally maps to escalating a decision into forced step-up, and `ProtectionOutcome` has no manual-review value (adding one is a materially larger change: policy seed data, thresholds, a migration). This mirrors how `ChallengePurpose.PRIVILEGED_OPERATION` sat declared-but-unused in ADR 0004 until #48 gave it a real caller.

### The one new behavior: challenge-provider failure degrades to `TEMPORARILY_BLOCK`

`ProtectionDecisionApplicationService.decide()` now catches `RuntimeException` around the existing `challengeService.create(...)` call (only reached when policy evaluation returned `REQUIRE_STEP_UP`). On failure, the outcome used for the persisted trace and the returned result is downgraded to `ProtectionOutcome.TEMPORARILY_BLOCK` — never silently `ALLOW` — and `degraded=true`/`degradationReason=CHALLENGE_PROVIDER_UNAVAILABLE` are recorded. Unlike the audit/outbox case, this is a *handled* degradation: the transaction is not rolled back, a decision is persisted, and the caller gets a real, explainable response instead of a raw `500`.

### Recording provenance (reuses ADR 0013's pattern)

`degraded`/`degradationReason` are added to `normalized_context` (no migration, same JSONB column already extended for signal provenance) and to `ProtectionDecisionResult`/`ProtectionDecisionMade`/`ProtectionDecisionResponse`. Because `ProtectionDecisionMade` already flows to `OutboxEventRecorder` (pseudonymized per ADR 0012) and `SecurityEventLogger`, a degraded decision is automatically visible in the integration event and the structured security log with no additional wiring.

### Metrics

`ProtectionMetricsRecorder` (already Micrometer-backed) gains a counter, `accountshield.protection.degraded_decisions`, tagged `reason`, incremented whenever `ProtectionDecisionMade.degraded()` is true — covers the challenge-provider path for free via the existing event listener. For the two reasons that produce *no* decision (`ACTIVE_POLICY_UNAVAILABLE`, `RISK_SIGNAL_STALE`), the same counter/tag scheme is incremented directly at the throw site inside `ProtectionDecisionApplicationService`, since no `ProtectionDecisionMade` event is ever published for those paths.

## Alternatives considered

- **Adding a new `ProtectionOutcome.REQUIRE_MANUAL_REVIEW` value** for challenge-provider failure — rejected as disproportionate for this PR (policy seed data, threshold migration); `TEMPORARILY_BLOCK` already satisfies "never accidentally `ALLOW`" and is available today.
- **Bespoke exception types for DB/audit/outbox failures** — rejected; both already fail closed by transactional rollback, and inventing exception types for them wouldn't change behavior, only add ceremony around something the transaction boundary already guarantees.
- **Storing `degraded`/`degradationReason` as dedicated `decision_trace` columns** — rejected in favor of extending `normalized_context`, consistent with how ADR 0013 added signal provenance; avoids a migration for what is currently a low-cardinality classification.

## Consequences

### Positive

- challenge-provider failures are now explainable and safe (`TEMPORARILY_BLOCK`, recorded reason) instead of a leaking a raw `500`;
- the classification model gives every future dependency-failure decision a place to live (`DegradationReason`) without re-deriving the taxonomy;
- metrics, structured logs, and the outbox integration event all pick up degraded decisions automatically through existing listeners.

### Negative

- `DegradationReason` currently only covers three of the six dependencies the issue names; DB/audit/outbox are documented as already-safe rather than given dedicated tests per-path;
- `REQUIRE_STEP_UP` and `REQUIRE_MANUAL_REVIEW` strategies are declared but unimplemented, which could be mistaken for dead code without this ADR's context;
- the `challengeService.create(...)` catch is a broad `RuntimeException`, which could mask a genuine programming bug as a "provider outage" — acceptable here since simulated challenge creation has no legitimate exception path today, but worth narrowing if a real provider integration introduces more specific failure types.

## Guardrails

- the challenge-provider catch never widens beyond the single `challengeService.create(...)` call;
- `effectiveOutcome` (not the original policy-evaluated outcome) is what gets persisted to the decision trace, published in the event, and returned to the caller — there is no path where a degraded decision reports its original, undegraded outcome;
- `ProtectionDecisionApplicationServiceTest.challengeProviderFailureDuringStepUpDegradesToTemporarilyBlock` and `ProtectionDecisionIntegrationTest.challengeProviderFailureDegradesToPersistedTemporarilyBlock` both assert the outcome is never `ALLOW`/`REQUIRE_STEP_UP` on this path.

## Revisit criteria

This decision should be revisited when:

- a real challenge provider is introduced with its own specific exception hierarchy (narrow the catch beyond `RuntimeException`);
- a genuine manual-review workflow and `ProtectionOutcome` value are introduced (would activate the currently-unused `REQUIRE_MANUAL_REVIEW` strategy);
- DB/audit/outbox failures need per-path metrics or tests beyond "the transaction rolled back."

## Links

- Issue #44
- [ADR 0012](0012-pseudonymous-subject-tokens-for-integration-events.md), [ADR 0013](0013-risk-signal-provenance-envelope.md) (established the `normalized_context` extension pattern this reuses)
- [docs/architecture/protection-decisions.md](../architecture/protection-decisions.md) (degradation strategy table)
- Tests: `DegradationReasonTest`, `ProtectionDecisionApplicationServiceTest`, `ProtectionDecisionIntegrationTest`, `ProtectionMetricsRecorderTest`, `SecurityEventLoggerTest`
