# ADR 0042: Privacy-minimized recovery investigation read API

- Status: Accepted
- Date: 2026-07-30
- Related issues: #71, #174
- Related ADRs: 0005, 0010, 0040, 0041

## Context

The recovery module (ADR 0005, ADR 0010) exposes only state-changing endpoints under `POST /api/v1/recovery/**`. `RecoveryInvestigationQuery.findByDecisionId` is a narrow internal projection consumed only by the `investigation` module's decision timeline (ADR 0041); it cannot locate a recovery flow by its own reference, page a queue, or apply operator filters.

Frontend issue #71 needs a read-only recovery queue and detail view analogous to the decision investigation console (#69, #70). Without an authorized read contract, the frontend would have to reconstruct recovery state from mutation responses, which are neither queryable nor guaranteed to remain available, or bypass module boundaries to query persistence directly.

## Decision

AccountShield exposes two narrow read-only operations owned entirely by the `recovery` module:

```text
POST /api/v1/operator/recoveries/search
POST /api/v1/operator/recoveries/investigate
```

Both require the backend role `SECURITY_OPERATOR` through the existing operator route rule and return `Cache-Control: no-store`. Bounded filters, cursors and the opaque recovery reference stay in the JSON body and never appear in a path or query string, for the same reasons as ADR 0040.

### Search

`RecoveryFlowSearchQuery` returns a deterministic keyset-paginated page ordered by `updated_at DESC, id DESC`, matching ADR 0040's cursor design. Supported filters: `status`, `classification`, `eventType`, `initiatedFrom`/`initiatedTo` (bounded to 31 days, mirroring `DecisionInvestigationQuery.MAX_TIME_WINDOW`), `eligibleBefore`/`eligibleAfter`, and a risk-score range. Each summary carries a masked subject reference; the recovery's own reference is returned in full because it is the primary operational identifier for the detail lookup.

### Detail

`RecoveryFlowDetailQuery` returns one recovery's minimized detail by opaque reference: flow status (including a derived `terminal` flag and `terminalAt`, since `RecoveryFlowEntity` has no separate terminal-transition timestamp beyond `updatedAt`), classification and its exact rule version, risk score, lifecycle timestamps, reviewer, and a challenge summary composed through the existing `ChallengeInvestigationQuery` port (the same port the `investigation` module already uses for decision timelines). The recovery module already legitimately depends on `challenge` for its own state machine (ADR 0005), so this composition introduces no new cross-module dependency.

The originating decision reference and protection-request reference are masked in the detail response. Unlike the recovery's own reference, these are cross-module identifiers the operator does not need in full to use this read surface, and masking them avoids turning this endpoint into a general-purpose pivot into the audit and protection modules.

### Module ownership

Both operations live inside the `recovery` module (`recovery.internal.web.RecoveryInvestigationController`), not a new top-level module. Unlike the decision timeline (ADR 0041), which had to aggregate across `audit`, `challenge`, `recovery` and `outbox` and therefore needed a dedicated composing module to avoid a reverse dependency into `audit`, this read surface only combines data the `recovery` module already owns or already legitimately depends on. Introducing a new module here would add indirection without preventing any dependency cycle.

### Problem Details

Both operations share one `RecoveryInvestigationProblemHandler` scoped to `RecoveryInvestigationController`, returning three stable redacted problem types: `invalid-recovery-investigation` (400, malformed request or cursor), `recovery-investigation-not-found` (404, well-formed but absent or unauthorized-safe reference), and `recovery-investigation-unavailable` (503, retryable). This is a deliberate simplification versus five separate search/detail problem types: both operations reject malformed input and unavailability identically, and only `investigate` can 404.

## Availability semantics

The challenge section of a detail response is reported as one of `AVAILABLE`, `NOT_APPLICABLE`, or `UNAVAILABLE`, matching ADR 0041's convention. `UNAVAILABLE` is used only when the flow recorded an identity-challenge reference but no matching challenge projection could be found; an empty list is never used to imply confirmed absence in that case. `partial` is true exactly when the challenge section is `UNAVAILABLE`.

## Alternatives considered

### Reuse `RecoveryInvestigationQuery`

Rejected. That port is keyed by decision ID, is consumed by a different module (`investigation`) for a different purpose, and returns a narrower projection. Repurposing it would couple two unrelated read paths.

### Add a new top-level `recovery-investigation` module

Rejected for the reasons in "Module ownership" above: no dependency cycle needs preventing, so a new module would only add indirection.

### Expose recovery authorization directive/status separately from flow status

Rejected. `RecoveryFlowEntity.classification` already carries the same operationally meaningful distinction (`IMMEDIATE`/`DELAYED`/`MANUAL_REVIEW`) as the authorization's `directive`, and `RecoveryFlowEntity.status` already reports the full state machine. Duplicating the authorization's own status would not add distinguishable information for an operator.

### Fabricate an attempt/retry counter

Rejected. `recovery.recovery_flow` does not persist an attempt count. Inventing one would misrepresent unavailable evidence as a real, trustworthy value, which ADR 0040 and ADR 0041 both reject.

## Consequences

### Positive

- frontend #71 can consume two generated operations behind its BFF adapter without reconstructing state from mutation responses;
- authorization, minimization and cursor design are backend-authoritative and consistent with #69/#70's already-generated client conventions;
- no new module or dependency cycle is introduced;
- partial challenge evidence is represented honestly instead of as an empty, confirmed-absent list.

### Negative

- the detail response duplicates selected fields already present in the flow entity and the mutation API's own `RecoveryResponse`, so both must be kept in sync if the entity's shape changes;
- masking the originating decision and protection-request references means an operator who needs to pivot into the decision-investigation console (#70) must already hold the correlation ID or decision reference through another authorized path.

## Executable guardrails

- controller/security tests assert `no-store`, stable Problem Details, and RBAC (missing authentication, wrong role, `SECURITY_OPERATOR`) in `SecurityIntegrationTest`;
- PostgreSQL integration tests in `RecoveryInvestigationIntegrationTest` verify masking, pagination stability, filters, not-found, and challenge-section availability;
- no recovery investigation controller or query implementation may import another module's `internal.persistence` package.

## Revisit criteria

Revisit this decision if:

- `recovery.recovery_flow` gains a real attempt/retry counter that should be surfaced;
- a future workflow needs the unmasked originating decision or protection-request reference from this endpoint;
- measured query cost requires a separately maintained read model for the queue.
