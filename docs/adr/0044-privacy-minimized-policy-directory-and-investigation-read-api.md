# ADR 0044: Privacy-minimized policy directory and investigation read API

- Status: Accepted
- Date: 2026-07-30
- Related issues: #73, #181
- Related ADRs: 0007, 0015, 0016, 0021, 0022, 0040, 0041, 0042, 0043

## Context

Policy lifecycle (#33/ADR 0016), canary rollout (#34/ADR 0022), and historical impact analysis (#35/ADR 0021) are all implemented, but every existing endpoint is gated to `POLICY_ADMIN` (`PolicyLifecycleController`, `PolicyRolloutController`) or `SIMULATION_ANALYST` (`PolicyImpactController`). No `SECURITY_OPERATOR`-authorized read surface exists — the same gap replay had before ADR 0043. Frontend issue #73 needs read-only visibility into policy versions, governance, rollout status, and impact analysis without granting operators admin or analyst privileges, and without the frontend reconstructing rollout/impact composition itself from multiple privileged calls.

Unlike decisions (#69/#70) and recoveries (#71), policy data is admin-configured and low-cardinality rather than per-event, so a search operation here does not need bounded time windows or keyset pagination the way decision/recovery search does.

## Decision

AccountShield exposes two narrow read-only operations under `/api/v1/operator/policies/**`, split across two modules by the same ownership rule ADR 0043 established: a module owns a read operation directly when the data is entirely its own, and `investigation` owns it when composition across modules is required.

### Directory listing — owned by `policy`

```text
POST /api/v1/operator/policies/search
```

No request filters. Returns a bounded (cap 200) list of per-key summaries: `policyKey`, `totalVersions`, `activeVersion`, `activeVersionActivatedAt`, `hasActiveRollout`. Backed by a new public port `policy.PolicyDirectoryQuery`, implemented by `policy.internal.PolicyDirectoryQueryService` using two new derived-query repository methods (`PolicyVersionRepository.findByPolicyKeyOrderByCreatedAtDesc`, `ClientPolicyRouteRepository.findByPolicyKeyOrderByClientIdAscEventTypeAsc`) — no schema change, no new persistence access pattern.

`PolicyDirectoryQuery` also declares `investigate(policyKey)`, returning the version history (reusing the existing `PolicyVersionSummary` record as-is, including governance and diagnostics) and routing scope. This method has no HTTP endpoint of its own in the `policy` module; it exists purely as the internal building block the `investigation` module composes below — mirroring how `audit.DecisionEvidenceQuery` exists only to be composed by `investigation.DecisionTimelineService` (ADR 0041).

### Investigation detail — owned by `investigation`

```text
POST /api/v1/operator/policies/investigate
```

Request body: `{ "policyKey": "..." }`. Composes, through module dependencies `investigation` already has:

- `policy.PolicyDirectoryQuery.investigate(policyKey)` — versions and routing scope;
- `policy.PolicyRolloutService.findActiveRollout(policyKey)` — the existing public read method, called unchanged;
- when an active rollout exists, `simulation.PolicyImpactAnalysisService.analyzeImpact(policyKey, rollout.candidateVersion(), 5000)` — the same side-effect-free engine `PolicyImpactController` already calls, reused as-is rather than duplicated.

No new module-level dependency edge is introduced: `investigation` already imports `policy` (via `DecisionReplayProblemHandler`'s `PolicyVersionNotFoundException` handling, added in #178) and already imports `simulation` (via `DecisionReplayService`, also #178).

Impact-analysis availability is explicit:

- `NOT_APPLICABLE` — no active rollout exists, so there is nothing to compare;
- `AVAILABLE` — an active rollout exists and analysis succeeded;
- `UNAVAILABLE` — an active rollout exists but analysis failed (for example a race where the candidate version was retired between the rollout lookup and the analysis call); any `RuntimeException` from the analysis call is caught at this single call site and mapped to `UNAVAILABLE` rather than propagating as a generic 503, while failures in the core lifecycle/rollout lookups still propagate normally.

`DivergentDecision.protectionRequestId` is masked before crossing the API boundary; `redactedAccountReference` is already HMAC-pseudonymized upstream by `AccountPseudonymizer` and passes through unchanged. `PolicyVersionSummary` and `PolicySegmentImpact` are reused directly from their owning modules without a parallel redefinition, since neither carries a field this ADR needs to hide — `PolicyVersionSummary`'s governance actor identities (`createdBy`/`validatedBy`/`approvedBy`) are exactly the "author/approver metadata where authorized" issue #73 asks for, and `SECURITY_OPERATOR` is that authorization.

## Alternatives considered

### Expose everything from a single controller in `policy`

Rejected. `investigate`'s impact-analysis composition needs `simulation.PolicyImpactAnalysisService`. Adding that dependency to `policy` would create a cycle, since `simulation` already depends on `policy` (`PolicyEvaluationService`, used by both replay and shadow evaluation). This is the same reasoning ADR 0041 used to justify a dedicated composing module for the decision timeline.

### Add a new top-level `policy-investigation` module

Rejected for the same reason ADR 0042 rejected it for recovery: `investigation` already owns exactly this composition role and already has the required dependencies; a new module would add indirection without preventing any cycle.

### Require an explicit candidate-version parameter on `investigate`

Rejected. The operator-facing view should show impact analysis for the policy's own in-progress canary, not an arbitrary hypothetical comparison — that remains `SIMULATION_ANALYST`'s `POST /api/v1/simulation/policy-impact`, unchanged and untouched by this ADR.

### Add cursor-based pagination to `search`

Rejected. Policy keys are bounded by admin configuration (typically single digits to low tens), not user or attacker-driven volume like decisions or recoveries. A capped, unpaginated list (200) is simpler and sufficient; revisit only if that assumption changes.

## Consequences

### Positive

- frontend #73 can consume two generated operations behind its BFF adapter, without ever calling `POLICY_ADMIN`/`SIMULATION_ANALYST` endpoints;
- no schema change, no new module-level dependency edge, no duplicated engine logic;
- impact-analysis unavailability is represented honestly instead of as fabricated zero divergence;
- author/approver identity remains visible to the role that legitimately needs it for separation-of-duties verification.

### Negative

- `policy.PolicyDirectoryQuery.investigate` is a public port with no HTTP endpoint of its own in its owning module, which can look unused without this ADR's context;
- the two-controller split (`policy` for search, `investigation` for investigate) requires a reader to know the ownership rule to find the composed endpoint.

## Executable guardrails

- `SecurityIntegrationTest` covers missing authentication, wrong role, and `SECURITY_OPERATOR` success for both endpoints;
- `PolicyInvestigationIntegrationTest` (PostgreSQL) verifies search summary correctness, ordered version history with governance and diagnostics, `NOT_APPLICABLE` impact when no rollout exists, `AVAILABLE` impact with masked divergent decisions when a rollout exists, and not-found for a well-formed but unknown policy key;
- `ArchitectureTest` (Spring Modulith boundary verification) passes with no new cycle;
- no policy directory or policy investigation controller/service may import another module's `internal.persistence` package.

## Revisit criteria

Revisit this decision if:

- policy key cardinality grows enough that `search`'s 200-item cap needs real pagination;
- the operator console needs to compare against an arbitrary candidate version rather than only the active rollout's;
- routing-scope data grows to include fields that should be minimized beyond `clientId`/`eventType`.
