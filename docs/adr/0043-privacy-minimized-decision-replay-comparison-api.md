# ADR 0043: Privacy-minimized decision replay comparison API

- Status: Accepted
- Date: 2026-07-30
- Related issues: #72, #178
- Related ADRs: 0019, 0020, 0040, 0041, 0042

## Context

ADR 0019/0020 (issue #43) implemented a complete deterministic replay engine, exposed only through `GET /api/v1/simulation/replay/{protectionRequestId}` (`SimulationController`, role `SIMULATION_ANALYST`). That surface predates the operator investigation console: it is keyed by `protectionRequestId`, not the `decisionReference` operators already hold from #69/#70, sits outside `/api/v1/operator/**`, and is authorized for a different actor entirely. Frontend issue #72 needs a read-only replay comparison reachable from the same decision an operator is already investigating, without operators gaining simulation-analyst privileges or the frontend resolving `protectionRequestId` itself.

## Decision

AccountShield exposes one additional narrow read-only operation:

```text
POST /api/v1/operator/decisions/replay
```

The request contains one validated opaque UUID `decisionReference` in a JSON body, identical in shape to `DecisionTimelineRequest`. The path falls under the existing `/api/v1/operator/decisions/**` → `SECURITY_OPERATOR` matcher, so no new `SecurityConfig` rule is needed.

The operation:

1. resolves `decisionReference` to a `protectionRequestId` and masked subject reference through the existing `audit.DecisionEvidenceQuery` port (no new persistence access, no duplicated lookup logic);
2. invokes `simulation.SimulationService.replay(protectionRequestId)` as-is — the existing analyst endpoint and this new operator endpoint share the exact same side-effect-free engine, so replay behavior cannot drift between the two authorization surfaces;
3. composes the result into a dedicated minimized projection in the `investigation` module (`DecisionReplayQuery`/`DecisionReplayService`), the same module that already composes decision timeline evidence (ADR 0041) and recovery/challenge summaries;
4. returns `Cache-Control: no-store`;
5. maps an unresolved decision reference, or a decision whose protection request cannot be replayed, to a stable 404;
6. maps an unresolvable historical algorithm or policy version (`UnknownAlgorithmVersionException`, `PolicyVersionNotFoundException`) to a stable, explicit 503 rather than falling back to current-version behavior or a fabricated match.

## Response scope

The response carries original and replayed outcome, risk score, risk band, and ordered risk reasons; the single historical policy key/version, algorithm version, normalized-input schema version, reason-catalog version, and decision-engine version (replay always reconstructs against the *recorded* policy version — there is no live/candidate policy-selection dimension to compare here, unlike the policy-impact analysis in #35); the engine's existing human-readable `mismatches` strings, passed through unchanged; and the overall `matches` boolean.

Recovery-classification ("directive") divergence is intentionally absent from the response. `SimulationService.replay` does not compute it (ADR 0020 scoped it out), and this API must not fabricate a value the engine never produced.

## Alternatives considered

### Reuse `GET /api/v1/simulation/replay/{protectionRequestId}` directly from the frontend

Rejected. It authorizes a different role (`SIMULATION_ANALYST`), is keyed by an identifier the operator console does not hold, and returns unminimized analyst-facing fields (e.g. a `protectionRequestId` in the response body) not appropriate for the operator surface's minimization conventions.

### Duplicate the replay reconstruction logic in the `investigation` module

Rejected. The engine is already deterministic, tested, and side-effect-free; duplicating it would risk the two authorization surfaces silently diverging in behavior.

### Add replay composition to the `simulation` module instead of `investigation`

Rejected. `simulation` has no existing dependency on `audit`'s minimized evidence port, and `investigation` already owns exactly this "compose module-owned ports into one operator projection" responsibility for the same `decisionReference` used by #70/#171. Adding the dependency there is consistent with the existing edge, not a new direction.

### Fabricate a `directive` comparison field

Rejected for the same reason ADR 0042 rejected fabricating a recovery attempt counter: representing unavailable engine output as a real value would misstate what was actually verified.

## Consequences

### Positive

- frontend #72 can consume one generated operation behind its BFF adapter, keyed by the same `decisionReference` already used by #69/#70;
- the analyst-only and operator-facing replay surfaces cannot behave differently, because they share one engine call;
- unavailable historical versions are explicit 503s instead of silent current-version fallback or a fabricated match;
- no new persistence access or top-level module is introduced.

### Negative

- the operator projection omits `protectionRequestId` and other analyst-facing fields (`ReplayResult`) present on the older endpoint; this is intentional minimization, not a bug;
- two authorization surfaces (`SIMULATION_ANALYST` and `SECURITY_OPERATOR`) now both call `SimulationService.replay`, so any future behavior change to the engine affects both by design.

## Executable guardrails

- `SecurityIntegrationTest` covers missing authentication, wrong role, and `SECURITY_OPERATOR` success;
- `DecisionReplayIntegrationTest` (PostgreSQL) verifies masking, exact-match replay, not-found for an unknown-but-well-formed reference, and rejection of a malformed reference — plus that the call creates zero rows in `outbox.outbox_event`, `recovery.recovery_flow`, and `challenge.challenge_plan`, proving side-effect freedom at the API boundary;
- no decision-replay controller or service may import another module's `internal.persistence` package.

## Revisit criteria

Revisit this decision if:

- recovery-classification divergence becomes computable and worth exposing;
- the operator console needs to replay against a *candidate* (not recorded) policy version, which would reuse #35's shadow-evaluation path instead;
- `SimulationService.replay`'s signature changes in a way that no longer suits both authorization surfaces.
